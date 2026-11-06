package dev.opeope.client.screen;

import dev.opeope.client.config.OpeProgress;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.Onboarding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * Ajustes de la fruta: cómo se ve el HUD, la accesibilidad y cómo responden las teclas. Cada cambio se guarda al
 * momento en config/opeope-ajustes.json. Se abre desde el libro, desde Opciones → Controles → «Ope Ope no Mi…» o con
 * la tecla «Ajustes de la fruta» (sin tecla de fábrica).
 */
public class SettingsScreen extends GameOptionsScreen {
	private static final int ROOM = 0xFF8FE3FF, SOFT = 0xFF9FE6FF, TAB = 0xFFC6B5FF, FORGE = 0xFFFFD9A0;

	private CyclingButtonWidget<OpeSettings.Pistas> pistasBtn;

	public SettingsScreen(Screen parent) {
		super(parent, MinecraftClient.getInstance().options, Text.literal("Ope Ope no Mi: ajustes"));
	}

	@Override
	protected void addOptions() {
		OpeSettings s = OpeSettings.get();
		String q = k(Keys.ROOM), tab = k(Keys.ROW2), r = k(Keys.TAKT), c = k(Keys.T_UNION);

		header("Interfaz", ROOM);
		pistasBtn = cycle("Pistas", OpeSettings.Pistas.values(), s.pistas, v -> s.pistas = v,
			v -> switch (v) {
				case SIEMPRE -> "Siempre";
				case NUEVO -> "Solo al empezar";
				case NUNCA -> "Nunca";
			},
			v -> switch (v) {
				case SIEMPRE -> "La tarjeta de pistas de arriba a la derecha se ve siempre completa.";
				case NUEVO -> "Las pistas se encogen solas cuando ya las has visto un rato.";
				case NUNCA -> "Sin tarjeta de pistas ni primeros pasos.";
			});
		this.body.addAll(List.<ClickableWidget>of(
			cycle("Interfaz", OpeSettings.Interfaz.values(), s.interfaz, v -> s.interfaz = v,
				v -> switch (v) {
					case COMPLETA -> "Completa";
					case SENCILLA -> "Sencilla";
					case MINIMA -> "Mínima";
				},
				v -> switch (v) {
					case COMPLETA -> "Todo el HUD de la fruta: la barra de técnicas, los estados, la lista de Scan y las pistas.";
					case SENCILLA -> "Menos cosas: solo los estados importantes y, de las pistas, los primeros pasos.";
					case MINIMA -> "Lo justo: el ROOM, los avisos y las filas de teclas; sin barra ni estados.";
				}),
			tamano(s),
			new Slider("Opacidad", 0.4, 1.0, 0.05, 100, " %", () -> s.opacidad, v -> s.opacidad = (float) v,
				"Cuánto tapan los paneles del HUD; con alto contraste nunca baja del 94 %."),
			cycle("Barra de técnicas", OpeSettings.Barra.values(), s.barra, v -> s.barra = v,
				v -> switch (v) {
					case ABAJO -> "Abajo";
					case VERTICAL -> "Vertical";
					case AL_USARLA -> "Al usarla";
					case OCULTA -> "Oculta";
				},
				v -> switch (v) {
					case ABAJO -> "La barra de técnicas abajo a la derecha, junto a la barra de objetos.";
					case VERTICAL -> "La barra de técnicas en columna, en el borde derecho.";
					case AL_USARLA -> "Abajo, pero se esconde tras 4 s sin usar la fruta.";
					case OCULTA -> "Sin barra de técnicas: los tiempos de espera se ven en el libro y en las filas.";
				}),
			pistasBtn,
			bool("Aviso grande del ROOM", s.avisoRoom, v -> s.avisoRoom = v,
				"Al crear, fijar, lanzar o cerrar el ROOM sale un aviso grande en el centro.",
				"Sin avisos grandes en el centro; el resto del HUD sigue igual.")));

		header("Accesibilidad", SOFT);
		this.body.addAll(List.<ClickableWidget>of(
			cycle("Alto contraste", OpeSettings.TriState.values(), s.contraste, v -> s.contraste = v,
				SettingsScreen::triName,
				v -> switch (v) {
					case MINECRAFT -> "Sigue la opción «Alto contraste» de Accesibilidad de Minecraft.";
					case SI -> "Paneles opacos, bordes blancos y texto más claro, sin sombras.";
					case NO -> "Paneles translúcidos aunque Minecraft tenga el alto contraste puesto.";
				}),
			bool("Menos movimiento", s.menosMovimiento, v -> s.menosMovimiento = v,
				"Sin sacudidas, sin cambios de campo de visión y sin rayas: el sonido y el HUD siguen igual.",
				"La cámara se mueve con el ROOM (sacudidas y golpes de campo de visión)."),
			new Slider("Sacudidas de cámara", 0.0, 1.0, 0.05, 100, " %", () -> s.sacudidas, v -> s.sacudidas = (float) v,
				"Cuánto se mueve la cámara con el ROOM; también cuenta «Efectos de distorsión» de Minecraft."),
			cycle("Destellos", OpeSettings.Destellos.values(), s.destellos, v -> s.destellos = v,
				v -> switch (v) {
					case MINECRAFT -> "Como Minecraft";
					case SI -> "Sí";
					case SUAVES -> "Suaves";
					case NO -> "No";
				},
				v -> switch (v) {
					case MINECRAFT -> "Sigue «Ocultar destellos de rayos» de Minecraft.";
					case SI -> "Destellos de pantalla completos al aterrizar el ROOM y al cortar.";
					case SUAVES -> "Destellos muy flojos, sin fogonazos blancos.";
					case NO -> "Ningún destello de pantalla completa.";
				}),
			bool("Narrar el ROOM", s.narrarRoom, v -> s.narrarRoom = v,
				"Con el narrador de Minecraft encendido, se leen los cambios de tu ROOM (tamaño, aterrizaje, dentro o fuera).",
				"El narrador no dice nada del ROOM.")));

		header("Controles", TAB);
		this.body.addAll(List.<ClickableWidget>of(
			cycle("ROOM", OpeSettings.ModoRoom.values(), s.modoRoom, v -> s.modoRoom = v,
				v -> v == OpeSettings.ModoRoom.MANTENER ? "Mantener" : "Alternar",
				v -> v == OpeSettings.ModoRoom.MANTENER
					? "Mantén " + q + " para abrir el ROOM y suéltala para fijarlo; " + q + " + rueda o clic mientras la mantienes."
					: "Toca " + q + " para crear o entrar en modo ROOM; vuelve a tocar para salir (doble toque: quitarlo)."),
			cycle("Quitar el ROOM", OpeSettings.Quitar.values(), s.quitarRoom, v -> s.quitarRoom = v,
				v -> v == OpeSettings.Quitar.TOQUE ? "Un toque" : "Doble toque",
				v -> v == OpeSettings.Quitar.TOQUE
					? "Un toque corto de " + q + " quita el ROOM."
					: "Hace falta un doble toque de " + q + " para quitar el ROOM: un toque suelto no hace nada."),
			fila("Segunda fila", s.modoTab, v -> s.modoTab = v, tab, "la segunda fila"),
			fila("Órdenes de Takt", s.modoR, v -> s.modoR = v, r, "las órdenes de Takt"),
			cycle("Bisturí", OpeSettings.Modo2.values(), s.modoBisturi, v -> s.modoBisturi = v,
				SettingsScreen::modo2Name,
				v -> v == OpeSettings.Modo2.MANTENER
					? "Mantén el clic derecho para el Bisturí; al soltarlo, corta."
					: "Toca el clic derecho para el Bisturí y vuelve a tocarlo para cortar (Esc lo cancela)."),
			cycle("Cirugía", OpeSettings.Modo2.values(), s.modoCirugia, v -> s.modoCirugia = v,
				SettingsScreen::modo2Name,
				v -> v == OpeSettings.Modo2.MANTENER
					? "Mantén " + tab + " + " + c + " para la Cirugía; al soltar, para."
					: "Mantén " + tab + " + " + c + " para empezarla: sigue sola hasta el próximo " + tab + " + " + c + "."),
			bool("Tab: lista de jugadores", s.listaJugadoresTab, v -> s.listaJugadoresTab = v,
				"En multijugador, mantener " + tab + " sin pulsar nada más enseña la lista de jugadores.",
				tab + " es solo la segunda fila: la lista de jugadores necesita otra tecla en Controles."),
			new Slider("Toque de R", 200, 600, 10, 1, " ms", () -> s.toqueR, v -> s.toqueR = (int) Math.round(v),
				"Una pulsación de " + r + " más corta que esto es un toque (coger); más larga, las órdenes."),
			new Slider("Doble toque", 200, 600, 10, 1, " ms", () -> s.dobleToque, v -> s.dobleToque = (int) Math.round(v),
				"El tiempo máximo entre dos toques para que cuenten como doble toque (" + r + " " + r + ", " + q + " " + q + ")."),
			new Slider("Espera de R", 80, 400, 10, 1, " ms", () -> s.esperaR, v -> s.esperaR = (int) Math.round(v),
				"Cuánto hay que mantener " + r + " para que las teclas pasen a ser órdenes de Takt."),
			new Slider("Espera de Cirugía", 200, 1000, 50, 1, " ms", () -> s.esperaCirugia, v -> s.esperaCirugia = (int) Math.round(v),
				"Cuánto hay que mantener " + tab + " + " + c + " para que la Unión pase a ser Cirugía.")));

		header("Más", FORGE);
		ButtonWidget teclas = ButtonWidget.builder(Text.literal("Teclas…"), b -> this.client.setScreen(new KeysScreen(this)))
			.width(150)
			.tooltip(Tooltip.of(Text.literal("Cambia las teclas de la fruta y mira con qué teclas de Minecraft chocan.")))
			.build();
		ButtonWidget pistas = ButtonWidget.builder(Text.literal("Volver a mostrar las pistas"), b -> {
				OpeProgress.resetHints();
				b.setMessage(Text.literal("Pistas: otra vez desde cero"));
			})
			.width(150)
			.tooltip(Tooltip.of(Text.literal("Las tarjetas de pistas y los avisos de una vez vuelven a salir como la primera vez.")))
			.build();
		ButtonWidget repetir = ButtonWidget.builder(Text.literal("Primeros pasos: repetir"), b -> {
				Onboarding.restart();
				if (s.pistas == OpeSettings.Pistas.NUNCA) {
					s.pistas = OpeSettings.Pistas.NUEVO;
					pistasBtn.setValue(OpeSettings.Pistas.NUEVO);
					OpeSettings.save();
				}
				b.setMessage(Text.literal("Primeros pasos: desde el principio"));
			})
			.width(150)
			.tooltip(Tooltip.of(Text.literal("Vuelve a enseñar los seis primeros pasos con la fruta.")))
			.build();
		ButtonWidget saltar = ButtonWidget.builder(Text.literal("Saltar los primeros pasos"), b -> {
				Onboarding.skip();
				b.setMessage(Text.literal("Primeros pasos: saltados"));
			})
			.width(150)
			.tooltip(Tooltip.of(Text.literal("Quita la tarjeta de los primeros pasos; se pueden repetir cuando quieras.")))
			.build();
		ButtonWidget reset = ButtonWidget.builder(Text.literal("Restablecer ajustes"), b -> this.client.setScreen(new ConfirmScreen(yes -> {
				if (yes) {
					OpeSettings.reset();
					this.client.setScreen(new SettingsScreen(this.parent));
				} else {
					this.client.setScreen(this);
				}
			}, Text.literal("¿Restablecer los ajustes de la fruta?"), Text.literal("Vuelven todos los de fábrica. Las teclas no cambian."))))
			.width(150)
			.tooltip(Tooltip.of(Text.literal("Pone todos estos ajustes como venían; las teclas se cambian aparte.")))
			.build();
		this.body.addAll(List.<ClickableWidget>of(teclas, pistas, repetir, saltar, reset));
	}

	@Override
	public void removed() {
		super.removed();
		OpeSettings.save();
	}

	// ------------------------------------------------------------------ piezas

	private void header(String name, int color) {
		this.body.addWidgetEntry(new TextWidget(310, 20, Text.literal(name), this.textRenderer).setTextColor(color).alignLeft(), null);
	}

	private static <E> CyclingButtonWidget<E> cycle(String label, E[] values, E current, Consumer<E> set,
			Function<E, String> name, Function<E, String> tip) {
		return CyclingButtonWidget.<E>builder(v -> Text.literal(name.apply(v)))
			.values(values)
			.initially(current)
			.tooltip(v -> Tooltip.of(Text.literal(tip.apply(v))))
			.build(0, 0, 150, 20, Text.literal(label), (b, v) -> {
				set.accept(v);
				OpeSettings.save();
			});
	}

	private static CyclingButtonWidget<Boolean> bool(String label, boolean current, Consumer<Boolean> set, String tipOn, String tipOff) {
		return CyclingButtonWidget.onOffBuilder(Text.literal("Sí"), Text.literal("No"))
			.initially(current)
			.tooltip(v -> Tooltip.of(Text.literal(v ? tipOn : tipOff)))
			.build(0, 0, 150, 20, Text.literal(label), (b, v) -> {
				set.accept(v);
				OpeSettings.save();
			});
	}

	private static CyclingButtonWidget<Integer> tamano(OpeSettings s) {
		return CyclingButtonWidget.<Integer>builder(v -> Text.literal(v < 0 ? "Pequeño" : v > 0 ? "Grande" : "Normal"))
			.values(new Integer[]{-1, 0, 1})
			.initially(s.tamano)
			.tooltip(v -> Tooltip.of(Text.literal(v > 0
				? "El HUD de la fruta más grande. Si no cabe, se queda en Normal."
				: v < 0 ? "El HUD de la fruta más pequeño que el resto de la interfaz." : "El mismo tamaño que la interfaz de Minecraft.")))
			.build(0, 0, 150, 20, Text.literal("Tamaño"), (b, v) -> {
				s.tamano = v;
				OpeSettings.save();
			});
	}

	private static CyclingButtonWidget<OpeSettings.ModoFila> fila(String label, OpeSettings.ModoFila current,
			Consumer<OpeSettings.ModoFila> set, String key, String what) {
		return cycle(label, OpeSettings.ModoFila.values(), current, set,
			v -> switch (v) {
				case MANTENER -> "Mantener";
				case ALTERNAR -> "Alternar";
				case UNA_TECLA -> "Una tecla";
			},
			v -> switch (v) {
				case MANTENER -> "Mantén " + key + " y pulsa la tecla: al soltar " + key + " se cierra " + what + ".";
				case ALTERNAR -> "Toca " + key + " y " + what + " se queda abierta hasta otro toque de " + key + " (o Esc).";
				case UNA_TECLA -> "Toca " + key + " y luego la tecla: no hace falta mantener.";
			});
	}

	/** El nombre de la tecla de verdad, corto: «Q», «Tab», «Botón lateral 4». */
	private static String k(Keys key) {
		KeyLabels.Glyph g = KeyLabels.of(key);
		return g.icon() == null ? g.text() : KeyLabels.text(key.key);
	}

	private static String triName(OpeSettings.TriState v) {
		return switch (v) {
			case MINECRAFT -> "Como Minecraft";
			case SI -> "Sí";
			case NO -> "No";
		};
	}

	private static String modo2Name(OpeSettings.Modo2 v) {
		return v == OpeSettings.Modo2.MANTENER ? "Mantener" : "Alternar";
	}

	/** Un deslizador con pasos fijos: «Opacidad: 85 %», «Toque de R: 320 ms». Guarda solo cuando cambia de paso. */
	private static final class Slider extends SliderWidget {
		private final String label, unit;
		private final double min, max, step, scale;
		private final DoubleConsumer set;
		private double last;

		Slider(String label, double min, double max, double step, double scale, String unit, DoubleSupplier get, DoubleConsumer set, String tip) {
			super(0, 0, 150, 20, Text.empty(), toSlider(get.getAsDouble(), min, max));
			this.label = label;
			this.unit = unit;
			this.min = min;
			this.max = max;
			this.step = step;
			this.scale = scale;
			this.set = set;
			this.last = stepped();
			setTooltip(Tooltip.of(Text.literal(tip)));
			updateMessage();
		}

		private static double toSlider(double v, double min, double max) {
			if (Double.isNaN(v)) return 0;
			return Math.max(0, Math.min(1, (v - min) / (max - min)));
		}

		private double stepped() {
			double v = min + this.value * (max - min);
			v = Math.round(v / step) * step;
			return Math.max(min, Math.min(max, v));
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal(label + ": " + Math.round(stepped() * scale) + unit));
		}

		@Override
		protected void applyValue() {
			double v = stepped();
			if (Math.abs(v - last) < 1e-9) return;
			last = v;
			set.accept(v);
			OpeSettings.save();
		}
	}
}
