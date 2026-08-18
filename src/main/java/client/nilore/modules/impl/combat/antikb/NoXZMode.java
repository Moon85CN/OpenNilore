package client.nilore.modules.impl.combat.antikb;

import java.util.concurrent.LinkedBlockingDeque;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.utils.misc.ChatUtil;

public class NoXZMode
        extends AntiKBMode {
    public static NoXZMode INSTANCE;
    // 对齐 res/VelocityModule 状态位: cһеј / cјхіср / xcаohoi
    public static boolean velocityHandled;      // cһеј: 本次击退已处理(被击飞/等待反击)
    public static boolean handlingVelocity;    // cјхіср: 已抓到击退包, 等待发起攻击
    public static int attackCount;             // xcаohoi: 剩余攻击次数
    public static boolean isAttacking;         // 供 KillAura 降 APS
    private boolean gotKnockback;              // һоаi: 收到过击退(保持疾跑用)
    private int onGroundTicks;                 // ееcхѕ: 击退后 tick 计数(>=5 清除)
    private int delayTicks;                    // pоiio: 等待延迟 tick
    private int retryCount;                    // ioрjеh: 未命中重试计数
    private boolean positionReset;             // xcјјоаc: 收到强制位置同步, 惰性重置
    private Entity target;                     // іһcсһh
    private long velocityEndTime = -1L;        // ѕсхx: 攻击完成时间戳, 用于关闭 velocityHandled
    private final LinkedBlockingDeque<Packet<ClientGamePacketListener>> packetQueue = new LinkedBlockingDeque();  // іхaіx

    public NoXZMode() {
        super("NoXZ");
        INSTANCE = this;
    }

    @Override
    public boolean isActive() {
        return this.velocityHandled;
    }

    @Override
    public void onEnable() {
        this.resetAll();
    }

    @Override
    public void onDisable() {
        this.resetAll();
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public void onRotation(RotationEvent rotationEvent) {
    }

    @Override
    public void onMotion(MotionEvent motionEvent) {
    }

    @Override
    public void onGameTick(GameTickEvent gameTickEvent) {
    }

    @Override
    public void onPreMotion(PreMotionEvent preMotionEvent) {
    }

    @Override
    public void onSprint(SprintEvent sprintEvent) {
    }

    // 对齐 res oоecһхh(非 Reduce 延迟分支 + Reduce 防御检查): 延迟击退包, 反击完成后重放
    @Override
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();
        if (packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket) {
            // 跨服/维度切换/重生: 立即重放延迟包并重置, 放行关键包, 防止延迟队列吞掉跨服同步导致卡住
            this.resetAll();
            return;
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
            // 对齐 res xcјјоаc: 强制位置同步 -> 惰性标记, 下 tick 重置
            this.positionReset = true;
            if (AntiKB.INSTANCE.debugLog.getValue()) {
                ChatUtil.print("Flag Detected");
            }
            return;
        }
        if (this.positionReset) {
            return;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            if (motion.getId() != mc.player.getId()) {
                return;
            }
            if (!this.canProcess()) {
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Alink Wait");
                }
                return;
            }
            if (!handlingVelocity) {
                handlingVelocity = true;
                this.delayTicks = 0;
            }
            this.velocityHandled = true;
            this.gotKnockback = true;
            receivePacketEvent.setCancelled(true);
            this.packetQueue.add(packet);
            return;
        }
        if (handlingVelocity) {
            // 放行玩家自己的移动包(否则客户端/服务器位置对不上), 其余位置/传送/无用包入队延迟
            if (packet instanceof ClientboundMoveEntityPacket move && move.getEntity(mc.level) == mc.player) {
                return;
            }
            if (packet instanceof ClientboundMoveEntityPacket
                    || packet instanceof ClientboundPingPacket
                    || packet instanceof ClientboundTeleportEntityPacket) {
                this.packetQueue.add(packet);
                receivePacketEvent.setCancelled(true);
            } else if (!this.isAllowedPacket(packet)) {
                this.packetQueue.add(packet);
                receivePacketEvent.setCancelled(true);
            }
        }
    }

    @Override
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.resetAll();
    }

    // 对齐 res eppоре(onTick) + ррјі(超时关闭): 攻击时机 = 准星命中目标 && 落地 && 攻击冷却就绪(Delay)
    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.velocityEndTime != -1L && System.currentTimeMillis() >= this.velocityEndTime) {
            velocityHandled = false;
            this.velocityEndTime = -1L;
        }
        this.target = this.getTarget();
        if (this.gotKnockback) {
            ++this.onGroundTicks;
        }
        if (this.onGroundTicks >= 5) {
            this.gotKnockback = false;
        }
        if (this.shouldIgnore() || this.retryCount >= 3) {
            this.resetAll();
            return;
        }
        if (handlingVelocity) {
            velocityHandled = true;
            if (this.delayTicks >= AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Alink Timeout");
                }
                this.resetAll();
                return;
            }
            ++this.delayTicks;
            if (!this.isAimingAt(this.target) || !mc.player.onGround()) {
                return;
            }
            if (mc.player.getAttackStrengthScale(0.0f) >= 1.0f) {
                // 对齐 res block23 攻击初始化: 重置计数, 重放延迟包
                attackCount = AntiKB.INSTANCE.maxCounter.getValue().intValue();
                this.retryCount = 0;
                handlingVelocity = false;
                this.flushQueue();
            } else {
                return;
            }
        }
        // 攻击循环: 每 tick 一次, 需持续准星命中+落地
        if (attackCount > 0) {
            if (!this.isAimingAt(this.target)) {
                ++this.retryCount;
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Miss");
                }
                return;
            }
            if (!mc.player.onGround()) {
                return;
            }
            this.doAttack(this.target);
            --attackCount;
            isAttacking = true;
            if (AntiKB.INSTANCE.debugLog.getValue()) {
                ChatUtil.print("Attack (" + attackCount + ")");
            }
            if (attackCount <= 0) {
                isAttacking = false;
                this.velocityEndTime = System.currentTimeMillis() + 10L;
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("done");
                }
            }
        }
    }

    // 对齐 res soіhр(onStrafe): AutoForwards 保持前进 + 击退后落地恢复疾跑
    @Override
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.velocityEndTime != -1L && System.currentTimeMillis() >= this.velocityEndTime) {
            velocityHandled = false;
            this.velocityEndTime = -1L;
        }
        if (AntiKB.INSTANCE.autoForwards.getValue() && velocityHandled) {
            strafeEvent.setForward(1.0f);
        }
        if (this.gotKnockback && mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 0.001) {
            strafeEvent.setSprinting(true);
            this.gotKnockback = false;
        }
    }

    // 对齐 res aѕрhspc: 防御/环境/目标缺失/Scaffold 时忽略
    private boolean shouldIgnore() {
        if (mc.player == null || mc.level == null) {
            return true;
        }
        if (mc.player.isRemoved() || mc.player.isDeadOrDying() || mc.player.getHealth() <= 0.0f) {
            return true;
        }
        if (this.positionReset || mc.getConnection() == null) {
            return true;
        }
        if (this.target == null) {
            return true;
        }
        return Scaffold.INSTANCE.isEnabled();
    }

    // 对齐 res Reduce 分支防御: Require KillAura
    private boolean canProcess() {
        return !AntiKB.INSTANCE.requireKillAura.getValue()
                || (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled());
    }

    // 目标: 优先 KillAura 目标; 关闭 Require KillAura 且 KillAura 未开时回退到准星实体
    private Entity getTarget() {
        if (KillAura.target != null) {
            return KillAura.target;
        }
        if (!AntiKB.INSTANCE.requireKillAura.getValue()
                && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            return ((EntityHitResult) mc.hitResult).getEntity();
        }
        return null;
    }

    // 对齐 res jcіhј: 准星(EntityHitResult)命中目标
    private boolean isAimingAt(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            return ((EntityHitResult) mc.hitResult).getEntity() == entity;
        }
        return false;
    }

    // 对齐 res hahoсј: 直接攻击+挥手, 不碰疾跑
    private void doAttack(Entity entity) {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        mc.gameMode.attack(mc.player, entity);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // 对齐 res рхіаxs(同步版): 重放全部延迟包
    private void flushQueue() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        Packet<ClientGamePacketListener> packet;
        while ((packet = this.packetQueue.poll()) != null) {
            try {
                packet.handle(mc.getConnection());
            } catch (Exception exception) {
                this.packetQueue.clear();
                break;
            }
        }
    }

    private boolean isAllowedPacket(Packet<?> packet) {
        return packet instanceof ClientboundSetEntityMotionPacket || packet instanceof ClientboundSetHealthPacket || packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket || packet instanceof ClientboundSoundPacket || packet instanceof ClientboundPlayerChatPacket || packet instanceof ClientboundPlayerCombatKillPacket || packet instanceof ClientboundContainerClosePacket || packet instanceof ClientboundHurtAnimationPacket || packet instanceof ClientboundSetTitleTextPacket || packet instanceof ClientboundSetPlayerTeamPacket || packet instanceof ClientboundSystemChatPacket || packet instanceof ClientboundDisconnectPacket || packet instanceof ClientboundAnimatePacket && ((ClientboundAnimatePacket)packet).getId() != mc.player.getId();
    }

    // 对齐 res xаaеxs: 全部状态复位 + 重放延迟包
    private void resetAll() {
        this.flushQueue();
        velocityHandled = false;
        handlingVelocity = false;
        isAttacking = false;
        attackCount = 0;
        this.gotKnockback = false;
        this.onGroundTicks = 0;
        this.delayTicks = 0;
        this.retryCount = 0;
        this.positionReset = false;
        this.velocityEndTime = -1L;
        this.target = null;
    }

    static {
        isAttacking = false;
        handlingVelocity = false;
        attackCount = 0;
    }
}
