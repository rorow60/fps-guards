package com.nicolas.fpsguard;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.Text;

/**
 * FPS Guard : commande /fps boost (on/off).
 * - Ne touche qu'à l'affichage (client) : jamais au monde ni à la simulation.
 * - Les réglages d'origine sont mémorisés et remis à la désactivation
 *   (et aussi quand on quitte le jeu), donc rien n'est changé pour de bon.
 * - Pendant le boost : FPS illimités, V-Sync coupée, et ajustement automatique
 *   pour garder les FPS au-dessus de 80.
 */
public class FpsGuardClient implements ClientModInitializer {

	private static final int MIN_FPS = 80;
	private static final int RAISE_FPS = 160;
	private static final int SAMPLE_TICKS = 20;
	private static final int LOWER_AFTER = 2;
	private static final int RAISE_AFTER = 10;
	private static final int COOLDOWN_SAMPLES = 4;

	private static final int MIN_VIEW = 6;
	private static final double MIN_ENTITY = 0.5;

	private boolean boostOn = false;

	private int origView;
	private double origEntity;
	private int origMaxFps;
	private boolean origVsync;

	private int tickCounter = 0;
	private int lowCount = 0;
	private int highCount = 0;
	private int cooldown = 0;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (boostOn && client.options != null) {
				disable(client.options);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(
				ClientCommandManager.literal("fps").then(
					ClientCommandManager.literal("boost").executes(ctx -> {
						MinecraftClient client = MinecraftClient.getInstance();
						GameOptions o = client.options;
						if (boostOn) {
							disable(o);
							ctx.getSource().sendFeedback(Text.literal(
								"§cFPS Boost désactivé §7(réglages d'origine remis)"));
						} else {
							enable(o);
							ctx.getSource().sendFeedback(Text.literal(
								"§aFPS Boost activé §7- refais /fps boost pour l'arrêter"));
						}
						return Command.SINGLE_SUCCESS;
					})
				)
			)
		);
	}

	private void enable(GameOptions o) {
		origView = o.getViewDistance().getValue();
		origEntity = o.getEntityDistanceScaling().getValue();
		origMaxFps = o.getMaxFps().getValue();
		origVsync = o.getEnableVsync().getValue();

		o.getMaxFps().setValue(260);        // 260 = illimité
		o.getEnableVsync().setValue(false);

		tickCounter = 0;
		lowCount = 0;
		highCount = 0;
		cooldown = 0;
		boostOn = true;
	}

	private void disable(GameOptions o) {
		boostOn = false;
		o.getViewDistance().setValue(origView);
		o.getEntityDistanceScaling().setValue(origEntity);
		o.getMaxFps().setValue(origMaxFps);
		o.getEnableVsync().setValue(origVsync);
	}

	private void onTick(MinecraftClient client) {
		if (!boostOn || client.world == null || client.options == null) {
			return;
		}
		if (++tickCounter < SAMPLE_TICKS) {
			return;
		}
		tickCounter = 0;

		if (cooldown > 0) {
			cooldown--;
			return;
		}

		GameOptions o = client.options;
		int fps = client.getCurrentFps();

		if (fps < MIN_FPS) {
			highCount = 0;
			if (++lowCount >= LOWER_AFTER) {
				lowCount = 0;
				if (lower(o)) {
					cooldown = COOLDOWN_SAMPLES;
				}
			}
		} else if (fps > RAISE_FPS) {
			lowCount = 0;
			if (++highCount >= RAISE_AFTER) {
				highCount = 0;
				if (raise(o)) {
					cooldown = COOLDOWN_SAMPLES;
				}
			}
		} else {
			lowCount = 0;
			highCount = 0;
		}
	}

	private boolean lower(GameOptions o) {
		double ent = o.getEntityDistanceScaling().getValue();
		if (ent > MIN_ENTITY) {
			o.getEntityDistanceScaling().setValue(Math.max(MIN_ENTITY, ent - 0.25));
			return true;
		}
		int view = o.getViewDistance().getValue();
		if (view > MIN_VIEW) {
			o.getViewDistance().setValue(view - 1);
			return true;
		}
		return false;
	}

	private boolean raise(GameOptions o) {
		int view = o.getViewDistance().getValue();
		if (view < origView) {
			o.getViewDistance().setValue(view + 1);
			return true;
		}
		double ent = o.getEntityDistanceScaling().getValue();
		if (ent < origEntity) {
			o.getEntityDistanceScaling().setValue(Math.min(origEntity, ent + 0.25));
			return true;
		}
		return false;
	}
}
