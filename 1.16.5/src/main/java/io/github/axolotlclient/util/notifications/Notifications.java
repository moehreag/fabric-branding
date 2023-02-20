package io.github.axolotlclient.util.notifications;

import lombok.Getter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

public class Notifications implements NotificationProvider {

	@Getter
	private static final Notifications Instance = new Notifications();

	public void addStatus(Text title, Text description){
		MinecraftClient.getInstance().getToastManager().add(SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, title, description));
	}

	public void addStatus(String titleKey, String descKey){
		addStatus(new TranslatableText(titleKey), new TranslatableText(descKey));
	}
}
