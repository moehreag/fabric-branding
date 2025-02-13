/*
 * Copyright © 2024 moehreag <moehreag@gmail.com> & Contributors
 *
 * This file is part of AxolotlClient.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 * For more information, see the LICENSE file.
 */

package io.github.axolotlclient.api;

import java.net.URI;
import java.util.function.Consumer;

import io.github.axolotlclient.util.OSUtil;
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.ButtonWidget;
import net.minecraft.text.CommonTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

public class PrivacyNoticeScreen extends Screen {

	private static final URI TERMS_URI = URI.create(Constants.TERMS);

	private final Screen parent;
	private final Consumer<Boolean> accepted;
	private MultilineText message;

	protected PrivacyNoticeScreen(Screen parent, Consumer<Boolean> accepted) {
		super(Text.translatable("api.privacyNotice"));
		this.parent = parent;
		this.accepted = accepted;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		renderBackground(graphics, mouseX, mouseY, delta);
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredShadowedText(this.textRenderer, this.title, this.width / 2, getTitleY(), -1);
		message.drawCenteredWithShadow(graphics, width / 2, getMessageY());
	}

	@Override
	public Text getNarratedTitle() {
		return CommonTexts.joinSentences(super.getNarratedTitle(),
			Text.translatable("api.privacyNotice.description"));
	}

	@Override
	protected void init() {

		message = MultilineText.create(client.textRenderer,
			Text.translatable("api.privacyNotice.description"), width - 50);
		int y = MathHelper.clamp(this.getMessageY() + this.getMessagesHeight() + 20, this.height / 6 + 96, this.height - 24);
		this.addButtons(y);
	}

	private void addButtons(int y) {
		addDrawableSelectableElement(ButtonWidget.builder(Text.translatable("api.privacyNotice.accept"), buttonWidget -> {
			client.setScreen(parent);
			APIOptions.getInstance().privacyAccepted.set(Options.PrivacyPolicyState.ACCEPTED);
			accepted.accept(true);
		}).positionAndSize(width / 2 - 50, y, 100, 20).build());
		addDrawableSelectableElement(ButtonWidget.builder(Text.translatable("api.privacyNotice.deny"), buttonWidget -> {
			client.setScreen(parent);
			APIOptions.getInstance().enabled.set(false);
			APIOptions.getInstance().privacyAccepted.set(Options.PrivacyPolicyState.DENIED);
			accepted.accept(false);
		}).positionAndSize(width / 2 - 50 + 105, y, 100, 20).build());
		addDrawableSelectableElement(ButtonWidget.builder(Text.translatable("api.privacyNotice.openPolicy"), buttonWidget -> {
			OSUtil.getOS().open(TERMS_URI);
		}).positionAndSize(width / 2 - 50 - 105, y, 100, 20).build());
	}

	private int getTitleY() {
		int i = (this.height - this.getMessagesHeight()) / 2;
		return MathHelper.clamp(i - 20 - 9, 10, 80);
	}

	private int getMessageY() {
		return this.getTitleY() + 20;
	}

	private int getMessagesHeight() {
		return this.message.count() * 9;
	}
}
