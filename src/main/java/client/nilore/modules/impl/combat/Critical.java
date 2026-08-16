package client.nilore.modules.impl.combat;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

/**
 * 照抄桌面 res/package_012/CriticalsModule.java (LiquidBounce 系 Criticals), 只保留
 * TargetTick 设置, 默认执行 Legit(1.9+ 合法暴击)逻辑:
 * - onPacket: 拦截攻击包(ServerboundInteractPacket)/放置包(ServerboundUseItemOnPacket),
 *   取消后先发伪造旋转移动包(ServerboundMovePlayerPacket.Rot)再重发原包
 * - onTick: 发 STOP_SPRINTING 包(1.9+ 暴击需非疾跑)
 * - canCrit: 冷却 + 落距窗口 <= TargetTick + 未来落点预测(参考 оіа)
 */
public class Critical extends Module {
    public static Critical INSTANCE;

    /* ======================== Settings (参考 CriticalsModule: 仅 TargetTick) ======================== */
    // 参考 јiс: 暴击落距窗口(tick)
    public final NumberSetting targetTick = new NumberSetting("TargetTick", 2, 0.1, 3, 0.1);

    /* ======================== State (参考 ѕѕроіeо/soх/ѕix/secа) ======================== */
    private boolean spoofing = false;
    private boolean sprintSent = false;
    private int tickCount = 0;
    private float lastDamage = 0.0f;
    private float lastPitch = 0.0f;

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.reset();
        super.onDisable();
    }

    /* ======================== onPacket (参考 oоecһхh) ======================== */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null) {
            return;
        }
        if (!event.isIncoming()) {
            return; // 只处理 outgoing(发送)包
        }
        Packet<?> packet = event.getPacket();
        if (this.canNotCrit()) {
            this.reset();
            return;
        }
        // 参考: tick 计数达 1(固定 Delay)且遇到疾跑命令包时重置
        if (this.tickCount >= 1) {
            this.sprintSent = true;
            if (packet instanceof ServerboundPlayerCommandPacket) {
                this.sprintSent = false;
                this.tickCount = 0;
            }
        }
        if (this.sprintSent) {
            return;
        }
        // 参考: 只处理攻击包(class_2824) + 放置方块包(class_2885)
        final boolean[] isAttack = {false};
        if (packet instanceof ServerboundInteractPacket interact) {
            interact.dispatch(new ServerboundInteractPacket.Handler() {
                @Override
                public void onAttack() {
                    isAttack[0] = true;
                }

                @Override
                public void onInteraction(InteractionHand hand) {
                }

                @Override
                public void onInteraction(InteractionHand hand, Vec3 location) {
                }
            });
        }
        if (!isAttack[0] && !(packet instanceof ServerboundUseItemOnPacket)) {
            return;
        }
        event.setCancelled(true);
        // 参考: 伪造旋转移动包, 优先用全局旋转(RotationHandler), 否则玩家当前旋转
        Rotation rot = RotationHandler.targetRotation != null
                ? RotationHandler.targetRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        float yaw = rot.getYaw() + (float) (Math.random() * 0.002 + 0.002);
        float pitch = rot.getPitch() - (float) (Math.random() * 0.002 + 0.002);
        float smoothedPitch = this.lastPitch + (pitch - this.lastPitch);
        PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(smoothedPitch, yaw, mc.player.onGround()));
        this.lastPitch = smoothedPitch;
        this.spoofing = true;
        PacketUtil.sendQueued((net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ServerGamePacketListener>) packet); // 重发原攻击/放置包
    }

    /* ======================== onTick (参考 һoе, STOP_SPRINTING) ======================== */
    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null) {
            return;
        }
        if (this.canNotCrit()) {
            return;
        }
        if (++this.tickCount == 1) {
            if (!this.spoofing) {
                PacketUtil.sendQueued(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            } else {
                this.spoofing = false;
            }
        }
    }

    /* ======================== 判定 ======================== */

    // 参考 сіһa(): 返回"不能暴击"
    private boolean canNotCrit() {
        if (mc.player == null || KillAura.target == null) {
            return true;
        }
        if (this.isUnCritable()) {
            return true;
        }
        if (mc.player.distanceTo(KillAura.target) > 9.0) {
            return true;
        }
        if (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) {
            return true;
        }
        return mc.player.getDeltaMovement().y > -0.08;
    }

    // 参考 ѕхi(): 异常状态(效果/潜行/使用物品/飞行/骑乘/梯子/水/岩浆)
    private boolean isUnCritable() {
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
        return mc.player.isInWater() || mc.player.isInLava();
    }

    // 参考 һрсріһp(): 当前攻击能否打出暴击(供 KillAura 集成)
    public boolean canCrit(Entity entity) {
        if (!this.isEnabled() || mc.player == null) {
            return false;
        }
        if (mc.player.isOnFire() || mc.player.isUsingItem()
                || mc.player.isFallFlying() || mc.player.isShiftKeyDown()) {
            return false;
        }
        if (!(entity instanceof LivingEntity target)) {
            return false;
        }
        float damage = this.getCritDamage();
        if (target.hurtTime > 0 && damage <= this.lastDamage) {
            return false;
        }
        if (this.isUnCritable()) {
            return false;
        }
        double velY = mc.player.getDeltaMovement().y;
        if (velY < -0.08) {
            this.lastDamage = damage;
            return false; // 正在下落, 等落地再判
        }
        float cooldown = mc.player.getAttackStrengthScale(0.5f);
        float fallTicks = Math.max(0.0f, (0.95f - cooldown) * mc.player.getCurrentItemAttackStrengthDelay());
        float dist = Math.max(fallTicks, (float) (velY / 0.08));
        if (dist > this.targetTick.getValue().floatValue()) {
            return false;
        }
        // 参考: оіа((int)(dist * 1.3f)) == null → 未来落点预测, 预测 tick 内会撞地则不能暴击
        return !this.wouldHitGround((int) (dist * 1.3f));
    }

    // 参考 paeсіoх(): 暴击伤害计算
    private float getCritDamage() {
        if (mc.player == null) {
            return -1.0f;
        }
        ItemStack stack = mc.player.getMainHandItem();
        float base = (float) ItemUtil.getAttackDamage(stack);
        float cooldown = mc.player.getAttackStrengthScale(0.5f);
        float damage = base * (0.2f + cooldown * cooldown * 0.8f);
        if (mc.player.getDeltaMovement().y < -0.08) {
            damage *= 1.5f;
        }
        return damage;
    }

    // 参考 оіа(int): 模拟下落, ticks tick 内会落地(撞到地面方块)则返回 true
    private boolean wouldHitGround(int ticks) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (mc.player.getDeltaMovement().y >= 0) {
            return false;
        }
        Vec3 start = new Vec3(mc.player.getX(), mc.player.getBoundingBox().minY, mc.player.getZ());
        BlockHitResult hit = mc.level.clip(new ClipContext(start, start.add(0.0, -30.0, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        double distance = start.y - hit.getLocation().y;
        double drop = 0.0;
        double velocity = mc.player.getDeltaMovement().y;
        for (int i = 0; i < ticks; i++) {
            drop += velocity;
            velocity = (velocity - 0.08) * 0.98;
            if (Math.abs(drop) >= distance) {
                return true;
            }
        }
        return false;
    }

    private void reset() {
        this.spoofing = false;
        this.sprintSent = false;
        this.tickCount = 0;
    }
}
