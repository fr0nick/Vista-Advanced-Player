package com.fr0nick.vista_advanced_player.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SetTvStatePayload(BlockPos pos, int ticks, boolean paused, float volume) implements CustomPacketPayload {
    public static final Type<SetTvStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("vista_advanced_player", "set_tv_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTvStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetTvStatePayload::pos, ByteBufCodecs.INT, SetTvStatePayload::ticks,
            ByteBufCodecs.BOOL, SetTvStatePayload::paused, ByteBufCodecs.FLOAT, SetTvStatePayload::volume, SetTvStatePayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}