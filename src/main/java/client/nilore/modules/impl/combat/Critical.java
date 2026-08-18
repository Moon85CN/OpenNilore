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

/**
 * Critical - 移植自 res/CriticalsModule.java (EdNaven, 反混淆)
 *
 * 只实现 1.9 分支:
 * - 每 TargetTick tick 发送 StatusOnly 包翻转服务器端 onGround 状态,
 *   配合 KillAura 松疾跑后的下落状态触发 1.9 暴击判定
 * - 拦截攻击包(PlayerInteract/PlayerAction), 先发送一个微调 yaw/pitch 的
 *   Rot 包, 再重发攻击包
 * - 疾跑中不干预(isUnCritable), 由 KillAura KeepSprint 协调疾跑状态
 */
public class Critical extends Module {
    public static Critical INSTANCE;

    // res схeрxjр: Mode(["Stuck","1.9"]) — 只做 1.9
    public final ModeSetting mode = new ModeSetting("Mode", "1.9", "Stuck").withDefault("1.9");
    // res jраһох: OnGround 包间隔(默认1, 1-3, step1)
    public final NumberSetting targetTick = new NumberSetting("TargetTick", 1, 1, 3, 1);
    // res aііоexѕ: 勾选后要求向前移动(zza>0)才暴击, 否则要求下落(deltaY<=-0.08)
    public final BooleanSetting moveCheck = new BooleanSetting("Move Check", false);
    // res јiс: 攻击冷却/下落容忍阈值(默认2, 1-3, step0.1)
    public final NumberSetting cooldown = new NumberSetting("Cooldown", 2.0, 1.0, 3.0, 0.1);

    private float lastDamage;   // res secа: 上次攻击伤害
    private boolean release;    // res soх: true = 暴击窗口已打开, 放行攻击包
    private boolean lookSent;   // res ѕѕроіeо: 本周期是否已发过 look 包
    private int tickCount;      // res ѕix: tick 计数器

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.reset();
        super.onDisable();
    }

    // res xаaеxs: 重置状态机
    private void reset() {
        this.lookSent = true;
        this.release = true;
        this.tickCount = (int) this.targetTick.getValue().floatValue();
    }

    // res oоecһхh: PacketSend 事件 — 拦截攻击包, 发 look 包 + 重发
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

        // 计数达到 TargetTick 时, 移动包重置计数(重新计时), 其它包锁定窗口并放行
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

        // 取旋转系统的目标旋转(若在旋转), 否则当前视角; yaw/pitch 各加随机微抖
        Rotation rotation = (RotationHandler.isRotating && RotationHandler.targetRotation != null)
                ? RotationHandler.targetRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        float pitch = rotation.getPitch() - (float) MathUtil.randomDouble(0.002, 0.004);
        float yaw = rotation.getYaw() + (float) MathUtil.randomDouble(0.002, 0.004);
        float pitchOut = Mth.wrapDegrees(pitch);

        // res: new LookAndOnGround(f2, callSite, onGround) + field_12887(yRot)=f2
        // mojmap: LookAndOnGround = Rot
        ServerboundMovePlayerPacket.Rot look =
                new ServerboundMovePlayerPacket.Rot(pitchOut, yaw, mc.player.onGround());
        ReflectionUtil.setYRot(look, pitchOut);
        this.lookSent = true;

        PacketUtil.sendQueued(look);
        PacketUtil.sendQueued((Packet<ServerGamePacketListener>) packet);
    }

    // res һoе: Tick 事件 — 计数 + 发送 OnGroundOnly 包
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
                // res: OnGroundOnly = StatusOnly
                PacketUtil.sendQueued(new ServerboundMovePlayerPacket.StatusOnly(mc.player.onGround()));
            } else {
                this.lookSent = false;
            }
        }
        // res: if (!soх) event.setCancelled(true) 取消 tick 冻结玩家;
        // nilore TickEvent 不可取消, 此步省略(由移动包自然驱动)
    }

    // res сiһa: 是否不可暴击
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

    // res ѕхi(bl): 药水/状态/疾跑检查
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

    // res еaјoһрѕ: 玩家 bounding box 周围有带方块实体(BaseEntityBlock)的方块时不可暴击
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

    // res һрсріһp: 供 KillAura 判断是否应对目标暴击(仅 1.9 mode)
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

    // res paeсіoх: 当前主手武器伤害(含冷却/暴击加成)
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

    // res mc.player.оіа((int)(f2 * 1.3f)) == null: 头顶 height 格内无方块
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
