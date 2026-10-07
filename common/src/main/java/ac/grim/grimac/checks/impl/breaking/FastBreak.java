package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.BlockBreakSpeed;
import ac.grim.grimac.utils.viaversion.ViaVersionUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.Set;

@CheckData(name = "FastBreak", stableKey = "grim.breaking.fast_break", description = "Breaking blocks too quickly")
public class FastBreak extends Check implements BlockBreakListener, PreViaPacketReceiveListener {
    private static final Verbose V =
            Verbose.of("[delay={ulong}ms|diff={f64:%.1f}ms, balance={f64:%.1f}ms], type={block}");

    private static final Set<StateType> EXEMPT_STATES = Set.of();

    private static final long BREAK_TOLERANCE_MS = 50L;
    private static final double FLAG_THRESHOLD = 1500.0;

    private final boolean clientOlderThanServer =
            PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion() > player.getClientVersion().getProtocolVersion();

    public FastBreak(GrimPlayer player) {
        super(player);
    }

    Vector3i targetBlockPosition = null;
    WrappedBlockState targetBlockState = null;
    double maximumBlockDamage = 0;
    long lastFinishBreak = 0;
    long startBreak = 0;

    double blockBreakBalance = 0;
    double blockDelayBalance = 0;

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == DiggingAction.START_DIGGING) {
            if (!ViaVersionUtil.isAvailable) {
                final WrappedBlockState defaultState =
                        WrappedBlockState.getDefaultState(player.getClientVersion(), blockBreak.block.getType());
                if (defaultState.getType() == StateTypes.AIR || EXEMPT_STATES.contains(defaultState.getType())) {
                    return;
                }
            }

            WrappedBlockState block = clientOlderThanServer
                    ? WrappedBlockState.getByGlobalId(
                            player.getClientVersion(),
                            player.getViaTranslatedClientBlockID(blockBreak.block.getGlobalId()))
                    : blockBreak.block;

            startBreak = System.currentTimeMillis() - BREAK_TOLERANCE_MS;
            targetBlockPosition = blockBreak.position;
            targetBlockState = block;

            maximumBlockDamage = BlockBreakSpeed.getBlockDamage(player, block);

            double breakDelay = System.currentTimeMillis() - lastFinishBreak;

            if (breakDelay >= 300 - BREAK_TOLERANCE_MS) {
                blockDelayBalance *= 0.9;
            } else {
                blockDelayBalance += Math.max(0.0, 300 - breakDelay - BREAK_TOLERANCE_MS) * 0.5;
            }

            if (blockDelayBalance > FLAG_THRESHOLD) {
                int type = VerboseCodecs.block(blockBreak.block.getType(), player.getClientVersion());
                if (flag(V.write(verbose()).bool(true).ulong((long) breakDelay).f64(0).f64(0).sint(type))
                        && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            }

            clampBalance();
        }

        if (blockBreak.action == DiggingAction.FINISHED_DIGGING
                && targetBlockPosition != null
                && targetBlockState != null) {

            maximumBlockDamage = Math.max(
                    maximumBlockDamage,
                    BlockBreakSpeed.getBlockDamage(player, targetBlockState)
            );

            if (maximumBlockDamage > 0) {
                double predictedTime = maximumBlockDamage >= 1.0
                        ? 0.0
                        : Math.ceil(1.0 / maximumBlockDamage) * 50.0;

                double realTime = System.currentTimeMillis() - startBreak;
                double diff = predictedTime - realTime;

                clampBalance();

                if (diff < BREAK_TOLERANCE_MS) {
                    blockBreakBalance *= 0.9;
                } else {
                    blockBreakBalance += diff - BREAK_TOLERANCE_MS;
                }

                if (blockBreakBalance > FLAG_THRESHOLD) {
                    int type = VerboseCodecs.block(blockBreak.block.getType(), player.getClientVersion());
                    if (flag(V.write(verbose()).bool(false).ulong(0).f64(diff).f64(blockBreakBalance).sint(type))
                            && shouldModifyPackets()) {
                        blockBreak.cancel();
                    }
                }
            }

            if (maximumBlockDamage >= 1.0) {
                lastFinishBreak = 0;
            } else {
                lastFinishBreak = System.currentTimeMillis();
            }

            targetBlockPosition = null;
            targetBlockState = null;
            maximumBlockDamage = 0;
        }

        if (blockBreak.action == DiggingAction.CANCELLED_DIGGING) {
            targetBlockPosition = null;
            targetBlockState = null;
            maximumBlockDamage = 0;
        }
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        boolean flying = WrapperPlayClientPlayerFlying.isFlying(event.getPacketType());

        if ((flying
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && isAnimation(event.getPacketType()))
                && targetBlockPosition != null
                && targetBlockState != null) {

            maximumBlockDamage = Math.max(
                    maximumBlockDamage,
                    BlockBreakSpeed.getBlockDamage(player, targetBlockState)
            );
        }
    }

    private void clampBalance() {
        double balance = Math.max(FLAG_THRESHOLD, player.getTransactionPing() * 2.0);

        blockBreakBalance = GrimMath.clamp(blockBreakBalance, -balance, balance);
        blockDelayBalance = GrimMath.clamp(blockDelayBalance, -balance, balance);
    }
}
