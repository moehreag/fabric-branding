package io.github.axolotlclient.mixin;

import io.github.axolotlclient.modules.hypixel.HypixelAbstractionLayer;
import io.github.axolotlclient.modules.hypixel.HypixelMods;
import io.github.axolotlclient.util.DiscordRPC;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin {

    @Inject(method = "init", at = @At("HEAD"))
    public void freePlayerData(CallbackInfo ci){
        if(HypixelMods.getInstance().cacheMode.get()!=null && Objects.equals(HypixelMods.getInstance().cacheMode.get(), HypixelMods.HypixelApiCacheMode.ON_CLIENT_DISCONNECT.toString())) {
            HypixelAbstractionLayer.clearPlayerData();
        }
    }

    @Inject(method = "connect()V", at = @At("HEAD"))
    public void connect(CallbackInfo ci){
        DiscordRPC.update();
    }
}
