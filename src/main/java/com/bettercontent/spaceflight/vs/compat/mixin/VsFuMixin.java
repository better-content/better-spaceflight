package com.bettercontent.spaceflight.vs.compat.mixin;

import com.bettercontent.spaceflight.vs.compat.internal.PinnedVs2411Bridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.events.JointSerializedEvent;
import org.valkyrienskies.core.impl.shadow.Ek;
import org.valkyrienskies.core.impl.shadow.FU;
import org.valkyrienskies.core.impl.shadow.HM;
import org.valkyrienskies.core.internal.joints.VSJoint;

/** Only the exact scoped old-body removal preserves Ek and hides REMOVED; RESTORED too. */
@Mixin(value = FU.class, remap = false)
public abstract class VsFuMixin {
    @Shadow(remap = false)
    private void b(int id, VSJoint joint, JointSerializedEvent.Operation operation) {
        throw new AssertionError("Mixin shadow not applied");
    }

    @Inject(method = "addJoint(Lorg/valkyrienskies/core/internal/joints/VSJoint;)I", at = @At("HEAD"),
        cancellable = true, require = 1, remap = false)
    private void spaceflight$rejectForeignCreate(VSJoint joint, CallbackInfoReturnable<Integer> cir) {
        if (!PinnedVs2411Bridge.guardedMutation((FU) (Object) this, -1, joint)) cir.setReturnValue(-1);
    }
    @Inject(method = "updateJoint(ILorg/valkyrienskies/core/internal/joints/VSJoint;)Z", at = @At("HEAD"),
        cancellable = true, require = 1, remap = false)
    private void spaceflight$rejectForeignUpdate(int id, VSJoint joint, CallbackInfoReturnable<Boolean> cir) {
        if (!PinnedVs2411Bridge.guardedMutation((FU) (Object) this, id, joint)) cir.setReturnValue(false);
    }
    @Inject(method = "removeJoint(I)Z", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void spaceflight$rejectForeignDelete(int id, CallbackInfoReturnable<Boolean> cir) {
        FU self = (FU) (Object) this;
        if (!PinnedVs2411Bridge.guardedMutation(self, id, self.getJointById(id))) cir.setReturnValue(false);
    }
    @Inject(method = "a(ILorg/valkyrienskies/core/internal/joints/VSJoint;)Z", at = @At("HEAD"),
        cancellable = true, require = 1, remap = false)
    private void spaceflight$guardExactIdRestore(int id, VSJoint joint, CallbackInfoReturnable<Boolean> cir) {
        if (!PinnedVs2411Bridge.guardedMutation((FU) (Object) this, id, joint)) cir.setReturnValue(false);
    }
    @Inject(method = "a(Lorg/valkyrienskies/core/impl/shadow/HM;Z)Z", at = @At("HEAD"),
        cancellable = true, require = 1, remap = false)
    private void spaceflight$rejectForeignRawJoint(HM joint, boolean flag, CallbackInfoReturnable<Boolean> cir) {
        if (!PinnedVs2411Bridge.nativeMutationAllowed((FU) (Object) this, joint)) cir.setReturnValue(false);
    }
    @Inject(method = "b(I)Z", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void spaceflight$rejectForeignRawRemove(int id, CallbackInfoReturnable<Boolean> cir) {
        if (!PinnedVs2411Bridge.guardedMutation((FU) (Object) this, id, null)) cir.setReturnValue(false);
    }

    @Redirect(method = "removeJoint(I)Z", at = @At(value = "INVOKE",
        target = "Lorg/valkyrienskies/core/impl/shadow/Ek;b(I)V", remap = false),
        require = 1, remap = false)
    private void spaceflight$retainLogicalId(Ek persisted, int id) {
        if (!PinnedVs2411Bridge.scopedDelete((FU) (Object) this, id)) persisted.b(id);
    }
    @Redirect(method = "removeJoint(I)Z", at = @At(value = "INVOKE",
        target = "Lorg/valkyrienskies/core/impl/shadow/FU;b(ILorg/valkyrienskies/core/internal/joints/VSJoint;Lorg/valkyrienskies/core/api/events/JointSerializedEvent$Operation;)V",
        remap = false), require = 1, remap = false)
    private void spaceflight$hideScopedRemoved(FU receiver, int id, VSJoint joint, JointSerializedEvent.Operation op) {
        if (!PinnedVs2411Bridge.scopedEvent(receiver, id, joint, op)) b(id, joint, op);
    }
    @Redirect(method = "a(ILorg/valkyrienskies/core/internal/joints/VSJoint;Lorg/valkyrienskies/core/api/events/JointSerializedEvent$Operation;)Z",
        at = @At(value = "INVOKE", target = "Lorg/valkyrienskies/core/impl/shadow/FU;b(ILorg/valkyrienskies/core/internal/joints/VSJoint;Lorg/valkyrienskies/core/api/events/JointSerializedEvent$Operation;)V",
            remap = false), require = 1, remap = false)
    private void spaceflight$hideScopedRestored(FU receiver, int id, VSJoint joint, JointSerializedEvent.Operation op) {
        if (!PinnedVs2411Bridge.scopedEvent(receiver, id, joint, op)) b(id, joint, op);
    }
}
