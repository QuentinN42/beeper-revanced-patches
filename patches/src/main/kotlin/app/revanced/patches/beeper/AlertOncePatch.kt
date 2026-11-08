package app.revanced.patches.beeper

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/revanced/extension/beeper/NotificationHelper;"

/**
 * [Method] with its list of patch targets: each entry is the instruction index
 * of a notify() call paired with the register holding the Notification argument.
 */
private data class MethodTargets(
    val method: Method,
    val targets: List<Pair<Int, Int>>,
)

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
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL) {
                // invoke-virtual/range is not expected: notify() takes at most
                // 3 arguments, which is always emitted as a 5-register call.
                return@forEachIndexed
            }

            val methodReference = (instruction as? ReferenceInstruction)
                ?.reference as? MethodReference
                ?: return@forEachIndexed

            // Match every notification posting call without caring about the exact
            // receiver class: NotificationManager, NotificationManagerCompat, wrappers...
            // notify(<...>, notification) is what identifies posting with a Notification.
            if (methodReference.name != "notify" ||
                methodReference.parameterTypes.size < 2 ||
                methodReference.parameterTypes.last() != "Landroid/app/Notification;"
            ) {
                return@forEachIndexed
            }

            // The Notification is the last argument of the call.
            // Registers for the first five arguments: A, B, C, D, E.
            val invoke = instruction as FiveRegisterInstruction
            val argumentIndex = methodReference.parameterTypes.size - 1
            val notificationRegister = when (argumentIndex) {
                0 -> invoke.registerA
                1 -> invoke.registerB
                2 -> invoke.registerC
                3 -> invoke.registerD
                else -> invoke.registerE
            }

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

    extendWith("extensions/extension.rve")

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
        classesToPatch.forEach { (classDef, methodTargets) ->
            val mutableClass = proxy(classDef).mutableClass

            methodTargets.forEach targets@{ (method, pairs) ->
                val mutableMethod: MutableMethod = mutableClass.methods.firstOrNull {
                    it.name == method.name && it.parameterTypes == method.parameterTypes
                } ?: return@targets

                pairs.forEach { (index, register) ->
                    // NotificationHelper mutates the notification object in place,
                    // so no result register is needed after the call.
                    mutableMethod.addInstructions(
                        index,
                        "invoke-static { v$register }, $EXTENSION_CLASS->" +
                                "modifyNotification(Landroid/app/Notification;)V",
                    )
                }
            }
        }
    }
}
