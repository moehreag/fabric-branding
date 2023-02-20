package io.github.axolotlclient.util.notifications;

import lombok.Getter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

public class Notifications implements NotificationProvider {

	@Getter
	private static final Notifications Instance = new Notifications();

	public void addStatus(String titleKey, String descKey, Object... args) {
		addStatus(Text.translatable(titleKey, args), Text.translatable(descKey, args));
	}

	public void addStatus(Text title, Text description) {
		MinecraftClient.getInstance().getToastManager().add(new SystemToast(SystemToast.Type.TUTORIAL_HINT, title, description));
	}
}
