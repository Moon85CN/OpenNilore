package client.nilore.modules.impl.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.phys.AABB;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.antikb.NoXZMode;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.math.MathUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.misc.ReflectionUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

public class Critical extends Module {
    public static Critical INSTANCE;

    public final ModeSetting mode = new ModeSetting("Mode", "1.9", "Stuck").withDefault("1.9");
    public final NumberSetting targetTick = new NumberSetting("TargetTick", 1, 1, 3, 1);
    public final BooleanSetting moveCheck = new BooleanSetting("Move Check", false);
    public final NumberSetting cooldown = new NumberSetting("Cooldown", 2.0, 1.0, 3.0, 0.1);

    private float lastDamage;
    private boolean release;
    private boolean lookSent;
    private int tickCount;

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.reset();
        super.onDisable();
    }

    private void reset() {
        this.lookSent = true;
        this.release = true;
        this.tickCount = (int) this.targetTick.getValue().floatValue();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.isIncoming()) {
            return;
        }
        if (mc.player == null) {
            return;
        }
        Packet<?> packet = event.getPacket();

        if (this.canNotCrit()) {
            this.reset();
            return;
        }

        if (this.tickCount >= (int) this.targetTick.getValue().floatValue()) {
            this.release = true;
            if (packet instanceof ServerboundMovePlayerPacket) {
                this.release = false;
                this.tickCount = 0;
            }
        }
        if (this.release) {
            return;
        }

        if (!(packet instanceof ServerboundInteractPacket)
                && !(packet instanceof ServerboundPlayerActionPacket)) {
            return;
        }

        event.setCancelled(true);

        Rotation rotation = (RotationHandler.isRotating && RotationHandler.targetRotation != null)
                ? RotationHandler.targetRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        float pitch = rotation.getPitch() - (float) MathUtil.randomDouble(0.002, 0.004);
        float yaw = rotation.getYaw() + (float) MathUtil.randomDouble(0.002, 0.004);
        float pitchOut = Mth.wrapDegrees(pitch);

        ServerboundMovePlayerPacket.Rot look =
                new ServerboundMovePlayerPacket.Rot(pitchOut, yaw, mc.player.onGround());
        ReflectionUtil.setYRot(look, pitchOut);
        this.lookSent = true;

        PacketUtil.sendQueued(look);
        PacketUtil.sendQueued((Packet<ServerGamePacketListener>) packet);
    }

    @EventTarget(value = 4)
    public void onPreMotion(PreMotionEvent event) {
        if (mc.player == null) {
            return;
        }
        if (this.canNotCrit()) {
            return;
        }

        if (++this.tickCount == (int) this.targetTick.getValue().floatValue()) {
            if (!this.lookSent) {
                PacketUtil.sendQueued(new ServerboundMovePlayerPacket.StatusOnly(mc.player.onGround()));
            } else {
                this.lookSent = false;
            }
        }
    }

    private boolean canNotCrit() {
        if (mc.player == null || KillAura.target == null) {
            return true;
        }
        if (this.mode.is("Stuck")) {
            return true;
        }
        if (this.isUnCritable(false) || NoXZMode.handlingVelocity) {
            return true;
        }
        if (mc.player.distanceTo(KillAura.target) > 9.0) {
            return true;
        }
        if (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) {
            return true;
        }
        if (this.moveCheck.getValue()) {
            return mc.player.zza <= 0.0f;
        }
        return mc.player.getDeltaMovement().y > -0.08;
    }

    private boolean isUnCritable(boolean allowSprint) {
        if (mc.player == null) {
            return true;
        }
        if (mc.player.hasEffect(MobEffects.BLINDNESS)
                || mc.player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)
                || mc.player.hasEffect(MobEffects.LEVITATION)) {
            return true;
        }
        if (mc.player.isShiftKeyDown() || mc.player.isUsingItem()
                || mc.player.isFallFlying() || mc.player.isPassenger()
                || mc.player.onClimbable()) {
            return true;
        }
        if (!allowSprint && mc.player.isSprinting()) {
            return true;
        }
        return mc.player.isInWater() || mc.player.isInLava() || this.isBlockedByTileEntity();
    }

    private boolean isBlockedByTileEntity() {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        AABB box = mc.player.getBoundingBox();
        for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); ++x) {
            for (int y = Mth.floor(box.minY); y < Mth.ceil(box.maxY); ++y) {
                for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); ++z) {
                    if (mc.level.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof BaseEntityBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean shouldCritTarget(Entity target) {
        if (!this.isEnabled() || !this.mode.is("1.9") || mc.player == null) {
            return false;
        }
        if (mc.player.isUsingItem() || mc.player.isFallFlying()
                || mc.player.onClimbable() || mc.player.isInWater()) {
            return false;
        }
        if (!(target instanceof LivingEntity living)) {
            return false;
        }

        float damage = this.getAttackDamage();
        if (living.hurtTime > 0 && damage <= this.lastDamage) {
            return false;
        }
        if (this.isUnCritable(false)) {
            return false;
        }

        double deltaY = mc.player.getDeltaMovement().y;
        if (deltaY < -0.08) {
            this.lastDamage = damage;
            return false;
        }
        float cooldown = mc.player.getAttackStrengthScale(0.5f);
        float f = Math.max(0.0f, (0.95f - cooldown) * mc.player.getAttackStrengthScale(0.0f));
        float f2 = Math.max(f, (float) (deltaY / 0.08));
        if (f2 > this.cooldown.getValue().floatValue()) {
            return false;
        }
        return !this.hasBlockAbove((int) (f2 * 1.3f));
    }

    private float getAttackDamage() {
        if (mc.player == null) {
            return -1.0f;
        }
        float progress = mc.player.getAttackStrengthScale(0.5f);
        float value = (float) ItemUtil.getAttackDamage(mc.player.getMainHandItem());
        value = value * (0.2f + progress * progress * 0.8f);
        if (!this.isUnCritable(false) && mc.player.getDeltaMovement().y < -0.08) {
            value *= 1.5f;
        }
        return value;
    }

    private boolean hasBlockAbove(int height) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        BlockPos pos = mc.player.blockPosition();
        for (int i = 1; i <= height; i++) {
            if (!mc.level.getBlockState(pos.above(i)).isAir()) {
                return true;
            }
        }
        return false;
    }
}
