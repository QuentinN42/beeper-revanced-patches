package app.revanced.patches.beeper

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/revanced/extension/beeper/NotificationHelper;"
private const val NOTIFICATION_TYPE = "Landroid/app/Notification;"
private const val TAG_TYPE = "Ljava/lang/String;"
private const val INT_TYPE = "I"

/**
 * The java.util.logging name must start with "app.revanced" for log records to
 * be propagated to the patch hosts' root logger and shown in their log output.
 */
private val patchLogger = java.util.logging.Logger.getLogger(
    "app.revanced.patches.beeper.AlertOncePatch",
)

/**
 * [Method] with its list of patch targets.
 */
private data class MethodTargets(
    val method: Method,
    val targets: List<Injection>,
)

private enum class SiteKind {
    NOTIFY,
    CANCEL,
}

/**
 * One injected call: the instruction index of the hooked call, the kind of
 * site, the smali of the static helper invocation and the register to move the
 * helper result into (null when the helper returns void).
 */
private data class Injection(
    val index: Int,
    val kind: SiteKind,
    val invoke: String,
    val resultRegister: Int?,
)

/**
 * Extract the register of the call instruction holding the parameter at
 * [parameterIndex] (0-based, excluding the receiver of instance calls).
 *
 * For instance calls the argument registers of the dex 35c format start one
 * slot after the receiver (registerC), for static calls they start directly at
 * registerC.
 */
private fun parameterRegister(
    instruction: Instruction,
    receiverOffset: Int,
    parameterIndex: Int,
): Int? = when (instruction) {
    is FiveRegisterInstruction -> when (receiverOffset + parameterIndex) {
        0 -> instruction.registerC
        1 -> instruction.registerD
        2 -> instruction.registerE
        3 -> instruction.registerF
        4 -> instruction.registerG
        else -> null
    }

    is RegisterRangeInstruction -> {
        val register = instruction.startRegister + receiverOffset + parameterIndex
        // The requested register must be part of the call.
        if (register < instruction.startRegister + instruction.registerCount) register else null
    }

    else -> null
}

/**
 * Build the smali of a static helper invocation passing [registers].
 *
 * The 5-register call format (35c) cannot encode registers above 15, which the
 * range format covers; range calls need consecutive registers, which holds
 * because helper parameters mirror the parameter order of the hooked call.
 */
private fun buildInvoke(registers: List<Int>, helperDescriptor: String): String? {
    if (registers.any { it > 65535 }) {
        return null
    }

    return if (registers.all { it <= 15 }) {
        val registerList = registers.joinToString(", ") { "v$it" }
        "invoke-static { $registerList }, $EXTENSION_CLASS->$helperDescriptor"
    } else {
        val start = registers.first()
        val end = registers.last()
        if (end - start != registers.size - 1) {
            // Non-consecutive registers cannot be passed by a range call.
            null
        } else {
            "invoke-static/range { v$start .. v$end }, $EXTENSION_CLASS->$helperDescriptor"
        }
    }
}

/**
 * Hook a notify() call: flag the notification and rewrite its id to a stable
 * per-conversation id, so repeated messages of a conversation are updates of
 * one notification instead of new alerts.
 */
private fun notifyInjection(
    index: Int,
    instruction: Instruction,
    methodReference: MethodReference,
): Injection? {
    val receiverOffset = when (instruction.opcode) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> 1
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> 0
        else -> return null
    }

    // Only the two canonical notify shapes are hooked. The helper parameters
    // mirror the call's parameter order, so the registers can be passed in the
    // original order, and the helper result replaces the id register.
    val parameters = methodReference.parameterTypes
    val idParameterIndex: Int
    val notificationParameterIndex: Int
    val helperDescriptor: String
    val parameterIndices: IntArray
    when (parameters) {
        listOf(INT_TYPE, NOTIFICATION_TYPE) -> {
            idParameterIndex = 0
            notificationParameterIndex = 1
            helperDescriptor = "(I$NOTIFICATION_TYPE)I"
            parameterIndices = intArrayOf(0, 1)
        }

        listOf(TAG_TYPE, INT_TYPE, NOTIFICATION_TYPE) -> {
            idParameterIndex = 1
            notificationParameterIndex = 2
            helperDescriptor = "($TAG_TYPE" + "I$NOTIFICATION_TYPE)I"
            parameterIndices = intArrayOf(0, 1, 2)
        }

        else -> return null
    }

    val idRegister =
        parameterRegister(instruction, receiverOffset, idParameterIndex) ?: return null

    // move-result (11x) only addresses registers up to 255; beyond that the id
    // cannot be rewritten, so fall back to only flagging the notification.
    if (idRegister <= 255) {
        val registers = parameterIndices.map { parameter ->
            parameterRegister(instruction, receiverOffset, parameter) ?: return null
        }
        val invoke = buildInvoke(registers, helperDescriptor) ?: return null

        return Injection(index, SiteKind.NOTIFY, invoke, idRegister)
    }

    val notificationRegister = parameterRegister(
        instruction,
        receiverOffset,
        notificationParameterIndex,
    ) ?: return null
    val invoke = buildInvoke(
        listOf(notificationRegister),
        "->setAlertOnce($NOTIFICATION_TYPE)V",
    ) ?: return null

    return Injection(index, SiteKind.NOTIFY, invoke, null)
}

/**
 * Hook a cancel() call: only cancels of the notification currently displayed
 * for a conversation are translated to the deduped id, so clearing still works
 * without allowing repeated cancels of superseded ids to clear the shared
 * notification.
 */
private fun cancelInjection(
    index: Int,
    instruction: Instruction,
    methodReference: MethodReference,
): Injection? {
    val receiverOffset = when (instruction.opcode) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> 1
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> 0
        else -> return null
    }

    val parameters = methodReference.parameterTypes
    if (parameters != listOf(INT_TYPE) && parameters != listOf(TAG_TYPE, INT_TYPE)) {
        // Other cancel overloads either take no id (cancelAll) or are
        // unrelated: hooking them is not needed.
        return null
    }

    // The id is the last parameter of both shapes; the tag is ignored.
    val idParameterIndex = parameters.size - 1
    val idRegister =
        parameterRegister(instruction, receiverOffset, idParameterIndex) ?: return null
    if (idRegister > 255) {
        return null
    }

    val invoke = buildInvoke(listOf(idRegister), "->translateCanceled(I)I") ?: return null

    return Injection(index, SiteKind.CANCEL, invoke, idRegister)
}

/**
 * Find every notify() and cancel() call in the class and the injections to
 * apply to them. Targets are reversed so they can be applied from the end
 * without invalidating earlier indices.
 */
private fun findTargets(classDef: ClassDef): List<MethodTargets> =
    classDef.methods.mapNotNull { method ->
        val targets = mutableListOf<Injection>()

        method.implementation?.instructions?.forEachIndexed { index, instruction ->
            when (instruction.opcode) {
                Opcode.INVOKE_VIRTUAL,
                Opcode.INVOKE_STATIC,
                Opcode.INVOKE_VIRTUAL_RANGE,
                Opcode.INVOKE_STATIC_RANGE,
                    -> Unit // Supported call formats, continue below.

                else -> return@forEachIndexed
            }

            val methodReference = (instruction as? ReferenceInstruction)
                ?.reference as? MethodReference
                ?: return@forEachIndexed

            val injection = when (methodReference.name) {
                "notify" -> notifyInjection(index, instruction, methodReference)
                "cancel" -> cancelInjection(index, instruction, methodReference)
                else -> null
            } ?: return@forEachIndexed

            targets.add(injection)
        }

        if (targets.isEmpty()) {
            null
        } else {
            MethodTargets(method, targets.asReversed())
        }
    }

@Suppress("unused")
val alertOncePatch = bytecodePatch(
    name = "Beeper alert once",
    description = "Only alert (sound and vibration) once per conversation. " +
            "Further messages update the existing notification silently until " +
            "it is dismissed.",
) {
    compatibleWith("com.beeper.android")

    // Use the non-inline extendWith overload with an explicit supplier.
    // The inline String overload resolves the extension resource through the
    // classloader of the patcher itself, whose classes are embedded in the
    // patching host (ReVanced Manager) and cannot see the patches file's
    // resources. This bundle's own classes are loaded from the patches dex
    // file, so their classloader resolves extensions/extension.rve from it.
    extendWith {
        object {}.javaClass.classLoader
            ?.getResourceAsStream("extensions/extension.rve")
            ?: throw PatchException(
                "Extension \"extensions/extension.rve\" not found",
            )
    }

    execute {
        // Collect every class to patch before proxying anything,
        // because proxying mutates the class list.
        val classesToPatch = buildList {
            classes.forEach { classDef ->
                val targets = findTargets(classDef)
                if (targets.isNotEmpty()) {
                    add(classDef to targets)
                }
            }
        }

        // Then proxy and patch each class.
        var notifiedSites = 0
        var canceledSites = 0
        classesToPatch.forEach { (classDef, methodTargets) ->
            val mutableClass = proxy(classDef).mutableClass

            methodTargets.forEach targets@{ (method, injections) ->
                val mutableMethod: MutableMethod = mutableClass.methods.firstOrNull {
                    it.name == method.name && it.parameterTypes == method.parameterTypes
                } ?: return@targets

                var notifyCount = 0
                var cancelCount = 0
                injections.forEach { injection ->
                    val smali = injection.resultRegister?.let { resultRegister ->
                        "${injection.invoke}\nmove-result v$resultRegister"
                    } ?: injection.invoke

                    mutableMethod.addInstructions(injection.index, smali)

                    when (injection.kind) {
                        SiteKind.NOTIFY -> notifyCount++
                        SiteKind.CANCEL -> cancelCount++
                    }
                }

                notifiedSites += notifyCount
                canceledSites += cancelCount
                patchLogger.info(
                    "Patched $notifyCount notify() and $cancelCount cancel() " +
                            "call site(s) in ${classDef.type}->${method.name}",
                )
            }
        }

        if (notifiedSites > 0) {
            patchLogger.info(
                "Hooked $notifiedSites notify() call site(s) with a stable " +
                        "per-conversation id and FLAG_ONLY_ALERT_ONCE",
            )
        } else {
            patchLogger.warning(
                "No notify() call with a Notification parameter was found; " +
                        "the patch did not modify the app",
            )

            throw PatchException(
                "No notify() calls the patch could hook were found in the app; " +
                        "repeated alerts will not be silenced. " +
                        "If this is a Beeper update changing its notification code, " +
                        "report an issue so the patch can be updated.",
            )
        }
    }
}
