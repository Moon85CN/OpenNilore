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
    public static boolean velocityHandled;
    public static boolean handlingVelocity;
    public static int attackCount;
    public static boolean isAttacking;
    private boolean gotKnockback;
    private int onGroundTicks;
    private int delayTicks;
    private int retryCount;
    private boolean positionReset;
    private Entity target;
    private long velocityEndTime = -1L;
    private final LinkedBlockingDeque<Packet<ClientGamePacketListener>> packetQueue = new LinkedBlockingDeque();

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

    @Override
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();
        if (packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket) {
            this.resetAll();
            return;
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
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
                attackCount = AntiKB.INSTANCE.maxCounter.getValue().intValue();
                this.retryCount = 0;
                handlingVelocity = false;
                this.flushQueue();
            } else {
                return;
            }
        }
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

    private boolean canProcess() {
        return !AntiKB.INSTANCE.requireKillAura.getValue()
                || (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled());
    }

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

    private boolean isAimingAt(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            return ((EntityHitResult) mc.hitResult).getEntity() == entity;
        }
        return false;
    }

    private void doAttack(Entity entity) {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        mc.gameMode.attack(mc.player, entity);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

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
