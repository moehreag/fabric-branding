package io.github.axolotlclient.mixin;

import io.github.axolotlclient.modules.hypixel.HypixelAbstractionLayer;
import io.github.axolotlclient.modules.hypixel.HypixelMods;
import io.github.axolotlclient.util.DiscordRPC;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(MultiplayerScreen.class)
public class MixinMultiplayerScreen {

    @Inject(method = "init", at = @At("HEAD"))
    public void freePlayerData(CallbackInfo ci){
        if(Objects.equals(HypixelMods.getInstance().cacheMode.get(), HypixelMods.HypixelCacheMode.ON_CLIENT_DISCONNECT.toString())){
            HypixelAbstractionLayer.clearPlayerData();
        }
    }

    @Inject(method = "connect()V", at = @At("HEAD"))
    public void connect(CallbackInfo ci){
        DiscordRPC.update();
    }
}
