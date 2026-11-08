package app.revanced.patches.beeper

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/revanced/extension/beeper/NotificationHelper;"

/**
 * [Method] with its list of patch targets: each entry is the instruction index
 * of a notify() call paired with the register holding the [Notification] argument.
 */
private data class MethodTargets(
    val method: Method,
    val targets: List<Pair<Int, Int>>,
)

@Suppress("unused")
val alertOncePatch = bytecodePatch(
    name = "Beeper alert once",
    description = "Only alert (sound and vibration) once per notification. " +
            "Further updates to the same notification are silent until it is dismissed.",
) {
    compatibleWith("com.beeper.android")

    extendWith("extensions/extension.rve")

    execute { context: BytecodePatchContext ->
        /**
         * Find every notify() call in the class and for each of them the instruction
         * index and the register holding its Notification argument. Targets are
         * reversed so patches can be applied from the end without invalidating
         * earlier indices.
         */
        fun findTargets(classDef: ClassDef): List<MethodTargets> =
            classDef.methods.mapNotNull { method ->
                val targets = method.implementation?.instructions
                    ?.mapIndexedNotNull { index, instruction ->
                        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) {
                            // invoke-virtual/range is not expected: notify() takes at most
                            // 3 arguments, which is always emitted as a 5-register call.
                            return@mapIndexedNotNull null
                        }

                        val methodReference = (instruction as? ReferenceInstruction)
                            ?.reference as? MethodReference
                            ?: return@mapIndexedNotNull null

                        // Match every notification posting call without caring about the
                        // exact receiver class: NotificationManager, NotificationManagerCompat,
                        // wrappers... The last parameter is what identifies notify(<...>, notification).
                        if (methodReference.name != "notify" ||
                            methodReference.parameterTypes.size < 2 ||
                            methodReference.parameterTypes.last() != "Landroid/app/Notification;"
                        ) {
                            return@mapIndexedNotNull null
                        }

                        // The Notification is the last argument of the call.
                        // Registers for the 5 first arguments: A, B, C, D, E.
                        val argumentIndex = methodReference.parameterTypes.size - 1
                        val notificationRegister = when (argumentIndex) {
                            0 -> instruction.registerA
                            1 -> instruction.registerB
                            2 -> instruction.registerC
                            3 -> instruction.registerD
                            else -> instruction.registerE
                        }

                        index to notificationRegister
                    }
                    ?.toList()
                    ?.asReversed()
                    ?: emptyList()

                if (targets.isEmpty()) {
                    null
                } else {
                    MethodTargets(method, targets)
                }
            }

        // Collect every class to patch before proxying anything,
        // because proxying mutates the class list.
        val classesToPatch = buildList {
            context.classes.forEach { classDef ->
                val targets = findTargets(classDef)
                if (targets.isNotEmpty()) {
                    add(classDef to targets)
                }
            }
        }

        // Then proxy and patch each class.
        classesToPatch.forEach { (classDef, methodTargets) ->
            val mutableClass = context.proxy(classDef).mutableClass

            methodTargets.forEach { (method, targets) ->
                val mutableMethod = mutableClass.methods.firstOrNull {
                    it.name == method.name &&
                            it.parameterTypes == method.parameterTypes
                } ?: continue

                targets.forEach { (index, register) ->
                    // Utils of NotificationHelper mutate the object in place,
                    // so no result register is needed after the call.
                    mutableMethod.addInstructions(
                        index,
                        "invoke-static { v$register }, ${EXTENSION_CLASS}->" +
                                "modifyNotification(Landroid/app/Notification;)V",
                    )
                }
            }
        }
    }
}
