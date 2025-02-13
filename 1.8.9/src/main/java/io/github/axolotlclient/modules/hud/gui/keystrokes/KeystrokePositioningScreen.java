package io.github.axolotlclient.modules.hud.gui.keystrokes;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.GlStateManager;
import io.github.axolotlclient.AxolotlClient;
import io.github.axolotlclient.AxolotlClientConfig.api.util.Colors;
import io.github.axolotlclient.AxolotlClientConfig.impl.ui.vanilla.widgets.VanillaButtonWidget;
import io.github.axolotlclient.modules.hud.HudEditScreen;
import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.hud.KeystrokeHud;
import io.github.axolotlclient.modules.hud.snapping.SnappingHelper;
import io.github.axolotlclient.modules.hud.util.DrawPosition;
import io.github.axolotlclient.modules.hud.util.DrawUtil;
import io.github.axolotlclient.modules.hud.util.Rectangle;
import io.github.axolotlclient.util.ClientColors;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;

public class KeystrokePositioningScreen extends io.github.axolotlclient.AxolotlClientConfig.impl.ui.Screen {
	private final Screen parent;
	private final KeystrokeHud hud;
	private KeystrokeHud.Keystroke focused;
	private final KeystrokeHud.Keystroke editing;

	public KeystrokePositioningScreen(Screen parent, KeystrokeHud hud, KeystrokeHud.Keystroke focused) {
		super(I18n.translate("keystrokes.stroke.move"));
		this.parent = parent;
		this.hud = hud;
		this.editing = focused;
		mouseDown = false;
	}

	public KeystrokePositioningScreen(Screen parent, KeystrokeHud hud) {
		this(parent, hud, null);
	}

	private DrawPosition offset = null;
	private boolean mouseDown;
	private SnappingHelper snap;

	@Override
	public void init() {
		addDrawableChild(new VanillaButtonWidget(width / 2 - 75, height - 50 + 22, 150, 20, I18n.translate("gui.back"), b -> closeScreen()));
		this.addDrawableChild(new VanillaButtonWidget(width / 2 - 50, height - 50, 100, 20, I18n.translate("hud.snapping") + ": "
			+ (I18n.translate(HudEditScreen.isSnappingEnabled() ? "options.on" : "options.off")),
			buttonWidget -> {
				HudEditScreen.toggleSnapping();
				buttonWidget.setMessage(I18n.translate("hud.snapping") + ": " +
					I18n.translate(HudEditScreen.isSnappingEnabled() ? "options.on" : "options.off"));
				AxolotlClient.configManager.save();
			}));
	}

	private float partialTick;
	@Override
	public void renderBackground() {
		GlStateManager.pushMatrix();
		GlStateManager.translatef(0, 0, -300);
		super.renderBackground();
		HudManager.getInstance().renderPlaceholder(partialTick);
		GlStateManager.popMatrix();
		fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTick) {
		this.partialTick = partialTick;
		super.render(mouseX, mouseY, partialTick);
		if (editing != null) {
			var rect = editing.getRenderPosition();
			if (rect.isMouseOver(mouseX, mouseY)) {
				DrawUtil.fillRect(rect, ClientColors.SELECTOR_BLUE.withAlpha(100));
			} else {
				DrawUtil.fillRect(rect, ClientColors.WHITE.withAlpha(50));
			}
			editing.render();
			DrawUtil.outlineRect(rect, Colors.BLACK);
		} else {
			hud.keystrokes.forEach(s -> {
				var rect = s.getRenderPosition();
				if (rect.isMouseOver(mouseX, mouseY)) {
					DrawUtil.fillRect(rect, ClientColors.SELECTOR_BLUE.withAlpha(100));
				} else {
					DrawUtil.fillRect(rect, ClientColors.WHITE.withAlpha(50));
				}
				s.render();
				DrawUtil.outlineRect(rect, Colors.BLACK);
			});
		}
		if (mouseDown && snap != null) {
			snap.renderSnaps();
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		var value = super.mouseClicked(mouseX, mouseY, button);
		if (button == 0) {
			Optional<KeystrokeHud.Keystroke> entry = Optional.empty();
			Optional<Rectangle> pos = Optional.empty();
			if (editing == null) {
				for (KeystrokeHud.Keystroke k : hud.keystrokes) {
					pos = Optional.of(k.getRenderPosition());
					if (pos.get().isMouseOver(mouseX, mouseY)) {
						entry = Optional.of(k);
						break;
					}
				}
			} else {
				pos = Optional.of(editing.getRenderPosition());
				if (pos.get().isMouseOver(mouseX, mouseY)) {
					entry = Optional.of(editing);
				}
			}
			if (entry.isPresent()) {
				focused = entry.get();
				mouseDown = true;
				var rect = pos.get();
				offset = new DrawPosition((int) Math.round(mouseX - rect.x()),
					(int) Math.round(mouseY - rect.y()));
				updateSnapState();
				return true;
			} else {
				focused = null;
			}
		}
		return value;
	}


	public void closeScreen() {
		minecraft.openScreen(parent);
		hud.saveKeystrokes();
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (focused != null) {
			hud.saveKeystrokes();
		}
		snap = null;
		mouseDown = false;
		focused = null;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (focused != null && mouseDown) {
			focused.setX((int) mouseX - offset.x());
			focused.setY((int) mouseY - offset.y());
			if (snap != null) {
				Integer snapX, snapY;
				var rect = focused.getRenderPosition();
				snap.setCurrent(rect);
				if ((snapX = snap.getCurrentXSnap()) != null) {
					focused.setX(snapX);
				}
				if ((snapY = snap.getCurrentYSnap()) != null) {
					focused.setY(snapY);
				}
			}
			return true;
		}
		return false;
	}

	private List<Rectangle> getAllBounds() {
		return Stream.concat(HudManager.getInstance().getAllBounds().stream(), hud.keystrokes.stream().map(KeystrokeHud.Keystroke::getRenderPosition)).toList();
	}

	private void updateSnapState() {
		if (HudEditScreen.isSnappingEnabled() && focused != null) {
			snap = new SnappingHelper(getAllBounds(), focused.getRenderPosition());
		} else if (snap != null) {
			snap = null;
		}
	}
}
