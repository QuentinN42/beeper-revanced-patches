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

/**
 * The java.util.logging name must start with "app.revanced" for log records to
 * be propagated to the patch hosts' root logger and shown in their log output.
 */
private val patchLogger = java.util.logging.Logger.getLogger(
    "app.revanced.patches.beeper.AlertOncePatch",
)

/**
 * [Method] with its list of patch targets: each entry is the instruction index
 * of a notify() call paired with the register holding the Notification argument.
 */
private data class MethodTargets(
    val method: Method,
    val targets: List<Pair<Int, Int>>,
)

/**
 * Extract the register holding the Notification argument of a notify() call.
 *
 * The Notification is the last argument of the call. For instance calls the
 * argument registers of the dex 35c format start one slot after the receiver
 * (registerC), for static calls they start directly at registerC:
 *
 * - `invoke-virtual {vC, vD, vE}, NotificationManager->notify(id, notification)`
 *   → receiver C, id D, notification E.
 * - `invoke-static {vC, vD}, NotificationManagerCompat->notify(id, notification)`
 *   → id C, notification D.
 */
private fun getNotificationRegister(
    instruction: Instruction,
    methodReference: MethodReference,
): Int? {
    val argumentIndex = methodReference.parameterTypes.size - 1
    val receiverOffset = when (instruction.opcode) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> 1
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> 0
        else -> return null
    }

    return when (instruction) {
        is FiveRegisterInstruction -> when (receiverOffset + argumentIndex) {
            0 -> instruction.registerC
            1 -> instruction.registerD
            2 -> instruction.registerE
            3 -> instruction.registerF
            4 -> instruction.registerG
            // notify() takes at most 3 arguments plus a receiver: this branch
            // is unreachable, but keeps the when exhaustive.
            else -> null
        }

        is RegisterRangeInstruction -> {
            val register = instruction.startRegister + receiverOffset + argumentIndex
            // The requested register must be part of the call.
            if (register < instruction.startRegister + instruction.registerCount) register else null
        }

        else -> null
    }
}

/**
 * Find every notify() call in the class and for each of them the instruction
 * index and the register holding its Notification argument. Targets are
 * reversed so patches can be applied from the end without invalidating
 * earlier indices.
 */
private fun findTargets(classDef: ClassDef): List<MethodTargets> =
    classDef.methods.mapNotNull { method ->
        val targets = mutableListOf<Pair<Int, Int>>()

        method.implementation?.instructions?.forEachIndexed { index, instruction ->
            when (instruction.opcode) {
                Opcode.INVOKE_VIRTUAL,
                Opcode.INVOKE_STATIC,
                Opcode.INVOKE_VIRTUAL_RANGE,
                Opcode.INVOKE_STATIC_RANGE,
                    -> Unit // Notification posting call format, continue below.

                else -> return@forEachIndexed
            }

            val methodReference = (instruction as? ReferenceInstruction)
                ?.reference as? MethodReference
                ?: return@forEachIndexed

            // Match every notification posting call without caring about the exact
            // receiver class: NotificationManager (instance), NotificationManagerCompat
            // (static), wrappers...
            // notify(<...>, notification) is what identifies posting with a Notification.
            if (methodReference.name != "notify" ||
                methodReference.parameterTypes.size < 2 ||
                methodReference.parameterTypes.last() != "Landroid/app/Notification;"
            ) {
                return@forEachIndexed
            }

            val notificationRegister = getNotificationRegister(instruction, methodReference)
                ?: return@forEachIndexed

            targets.add(index to notificationRegister)
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
    description = "Only alert (sound and vibration) once per notification. " +
            "Further updates to the same notification are silent until it is dismissed.",
) {
    compatibleWith("com.beeper.android")

    // Use the non-inline extendWith overload with an explicit supplier.
    // The inline String overload resolves the resource through the classloader
    // of the patcher itself, whose classes are embedded in the patching host
    // (ReVanced Manager) and cannot see the patches file's resources. This
    // bundle's own classes are loaded from the patches dex file, so their
    // classloader resolves extensions/extension.rve from the patches file.
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
        var injected = 0
        classesToPatch.forEach { (classDef, methodTargets) ->
            val mutableClass = proxy(classDef).mutableClass

            methodTargets.forEach targets@{ (method, pairs) ->
                val mutableMethod: MutableMethod = mutableClass.methods.firstOrNull {
                    it.name == method.name && it.parameterTypes == method.parameterTypes
                } ?: return@targets

                pairs.forEach { (index, register) ->
                    // NotificationHelper mutates the notification object in place,
                    // so no result register is needed after the call. The 5-register
                    // call format (35c) cannot encode registers above 15, which the
                    // range format covers.
                    val invoke =
                        if (register < 16) "invoke-static { v$register }, "
                        else "invoke-static/range { v$register .. v$register }, "

                    mutableMethod.addInstructions(
                        index,
                        "$invoke$EXTENSION_CLASS->" +
                                "modifyNotification(Landroid/app/Notification;)V",
                    )
                }

                injected += pairs.size
                patchLogger.info(
                    "Patched ${pairs.size} notify() call site(s) in " +
                            "${classDef.type}->${method.name}",
                )
            }
        }

        if (injected > 0) {
            patchLogger.info(
                "Flagged $injected notify() call site(s) with FLAG_ONLY_ALERT_ONCE",
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
