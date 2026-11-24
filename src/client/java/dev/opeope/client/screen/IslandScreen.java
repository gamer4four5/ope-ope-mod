package dev.opeope.client.screen;

import dev.opeope.client.ClientState;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.Send;
import dev.opeope.power.Actions;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Panel interactivo de Isla Bisturí, Misiones, Estilos de Combate y Modo Libre (Tecla J).
 */
public class IslandScreen extends Screen {
	private final Screen parent;

	public IslandScreen(Screen parent) {
		super(Text.literal("Isla Bisturí · Estructuras, Estilos y Misiones"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int y = 20;
		int colW = 154;
		int gap = 8;
		int leftX = cx - colW - gap / 2;
		int rightX = cx + gap / 2;

		// Selector rápido de estilos arriba
		int styleW = 100;
		int curStyle = ClientState.combatStyle();
		addDrawableChild(ButtonWidget.builder(
				Text.literal((curStyle == 1 ? "§b● " : "§7○ ") + "1: Fruta Ope Ope"),
				b -> { Send.action(Actions.STYLE_SWITCH, 1); close(); })
			.dimensions(cx - styleW - styleW / 2 - 6, y, styleW, 18)
			.tooltip(Tooltip.of(Text.literal("Estilo 1: Poderes clásicos y despertados de la Ope Ope no Mi.")))
			.build());
		addDrawableChild(ButtonWidget.builder(
				Text.literal((curStyle == 2 ? "§d● " : "§7○ ") + "2: Espada Kikoku"),
				b -> { Send.action(Actions.STYLE_SWITCH, 2); close(); })
			.dimensions(cx - styleW / 2, y, styleW, 18)
			.tooltip(Tooltip.of(Text.literal("Estilo 2: Equipa automáticamente la Kikoku. " + k(Keys.RADIO_KNIFE) + ": Iai · " + k(Keys.INJECTION)
				+ ": Luna de Acero · " + k(Keys.GAMMA) + ": Torbellino · " + k(Keys.AWAKEN) + ": Mil Puntadas.")))
			.build());
		addDrawableChild(ButtonWidget.builder(
				Text.literal((curStyle == 3 ? "§6● " : "§7○ ") + "3: Pelea / Palma"),
				b -> { Send.action(Actions.STYLE_SWITCH, 3); close(); })
			.dimensions(cx + styleW / 2 + 6, y, styleW, 18)
			.tooltip(Tooltip.of(Text.literal("Estilo 3: Artes marciales quirúrgicas. Clic izq: Combo 4 golpes · " + k(Keys.RADIO_KNIFE) + ": Mes · "
				+ k(Keys.INJECTION) + ": Counter Shock · " + k(Keys.GAMMA) + ": Trueque · " + k(Keys.SCAN) + ": Gancho Takt.")))
			.build());

		y += 21;

		// Cambio rápido entre Fruta Ope Ope (Law) y Fruta Gravity (Fujitora)
		addActionBtn(leftX, y, colW * 2 + gap,
			ClientState.meraFruit()
				? "🔥 Fruta Activa: Mera Mera (Ace) → Cambiar a Ope Ope (Law)"
				: ClientState.gravityFruit()
				? "🌌 Fruta Activa: Gravity (Fujitora) → Cambiar a Mera Mera (Ace)"
				: "💠 Fruta Activa: Ope Ope (Law) → Cambiar a Gravity (Fujitora)",
			"Pasa por las tres frutas: Ope Ope no Mi (Law), Zushi Zushi no Mi (Fujitora) y Mera Mera no Mi (Ace), conservando tu rango y recompensa.",
			19);
		y += 20;

		// Estructuras cortables de Isla Bisturí (al cortarlas, la parte superior queda bajo tu control automáticamente)
		addActionBtn(leftX, y, colW, "🏠 Casa Isla Bisturí", "Genera una casa frente a ti. Al cortarla, la mitad superior queda bajo tu control con la mirada.", 1);
		addActionBtn(rightX, y, colW, "🚢 Barco de Guerra Marina", "Genera un buque de guerra de 17 bloques con velas, cañones y el Capitán Smoker.", 6);
		y += 19;

		addActionBtn(leftX, y, colW, "🗼 Torre de Vigilancia", "Genera una torre alta ideal para cortes horizontales, diagonales o en dados.", 2);
		addActionBtn(rightX, y, colW, "📜 Puesto Marina y Misiones", "Genera el puesto de mando con cofre de suministros y tablón de misiones.", 7);
		y += 19;

		addActionBtn(leftX, y, colW, "🌉 Puente y Muralla", "Genera un puente elevado con pilares para seccionar y mover en el aire.", 3);
		addActionBtn(rightX, y, colW, "⚔ Escuadrón Pirata", "Genera un escuadrón de enemigos con botín dentro de tu ROOM.", 5);
		y += 19;

		addActionBtn(leftX, y, colW, "🪨 Formación Rocosa", "Genera un monolito de roca y minerales para cortar o forjar armas con R+1..5.", 4);
		addActionBtn(rightX, y, colW,
			ClientState.freeMode() ? "⚡ Modo Libre: ENCENDIDO" : "⚡ Modo Libre (0 Cooldown)",
			"Activa o desactiva el Modo Libre sin enfriamientos y con curación quirúrgica.", 8);
		y += 20;

		// Mejoras de ROOM y Despertar
		addActionBtn(leftX, y, colW, "⬆ Ampliar ROOM (+12 blq)", "Amplía el radio máximo y actual de tu ROOM en +12 bloques.", 9);
		addActionBtn(rightX, y, colW, "✨ Desbloquear / Despertar", "Desbloquea y alterna el Despertar (K-ROOM, R-ROOM, Shock Wille, Puncture Wille).", 10);
		y += 19;

		// Técnicas definitivas de la Fruta Ope Ope
		addActionBtn(leftX, y, colW, "🗡 K-ROOM: Kikoku 26m", "Recubre la Kikoku con K-ROOM y extiende una hoja gigante 3D de 26 metros que atraviesa muros y enemigos.", 11);
		addActionBtn(rightX, y, colW, "💥 Cráter Puncture Wille", "Perfora la tierra con la Kikoku gigante de 26m y abre un cráter abismal real con relámpago interno.", 14);
		y += 19;

		addActionBtn(leftX, y, colW, "♾ Cirugía Juventud Eterna", "Furo Shujutsu: Otorga 90s de Inmortalidad Quirúrgica Activa con halo 3D dorado-carmesí y purga total.", 12);
		addActionBtn(rightX, y, colW, "🏹 Shambles: Ráfaga Flechas", "Invoca un anillo de proyectiles quirúrgicos en el ROOM y los teletransporta contra el objetivo.", 13);
		y += 19;

		// Nuevos Poderes Quirúrgicos Supremos
		addActionBtn(leftX, y, colW,
			ClientState.zeroGravity() ? "🌌 Gravedad Cero: ACTIVA" : "🌌 Gravedad Cero + Vuelo",
			"Hace levitar sin gravedad a todos en el ROOM con anillos 3D y te permite volar dentro del ROOM (Doble Espacio).", 15);
		addActionBtn(rightX, y, colW, "☄ Takt Colosal: Meteorito", "Arranca un Meteorito Gigante bajo tu control con la mirada. Haz Clic Izquierdo para estrellarlo.", 16);
		y += 19;

		addActionBtn(leftX, y, colW, "⚙ Sterben: Sierra Kikoku 3D", "Lanza la Kikoku girando como una sierra circular gigante 3D que corta enemigos y edificios.", 17);
		addActionBtn(rightX, y, colW, "🧠 Kokoro Shambles: Mentes", "Intercambia las almas y cuerpos de enemigos en el ROOM y los convierte en tus aliados quirúrgicos.", 18);
		y += 21;

		// Pie de pantalla: Libro, Ajustes y Cerrar
		addDrawableChild(ButtonWidget.builder(Text.literal("📖 Libro de la fruta"), b -> this.client.setScreen(new GuideScreen()))
			.dimensions(cx - 154, y, 100, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("⚙ Ajustes y Teclas"), b -> this.client.setScreen(new SettingsScreen(this)))
			.dimensions(cx - 50, y, 100, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cerrar"), b -> close())
			.dimensions(cx + 54, y, 100, 18).build());
	}

	/** La tecla de verdad de la fruta (las técnicas de los estilos 2 y 3 van en las teclas de las de la fruta). */
	private static String k(Keys key) {
		return KeyLabels.name(key.key);
	}

	private void addActionBtn(int x, int y, int w, String label, String tooltip, int code) {
		addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
				Send.action(Actions.ISLAND_ACTION, code);
				close();
			})
			.dimensions(x, y, w, 18)
			.tooltip(Tooltip.of(Text.literal(tooltip)))
			.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 8, ClientState.meraFruit() ? 0xFFFF8A2A : ClientState.gravityFruit() ? 0xFFC266FF : 0xFF8FE3FF);
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("§7Flechas §f↑↓←→§7 mueven el ROOM · §fY§7 cambia estilo · §fC§7 con pieza elegida crea §bQuimera§7 al clicar un mob"),
			cx, this.height - 16, 0xFFAAAAAA);
	}

	@Override
	public void close() {
		if (this.client != null) this.client.setScreen(parent);
	}
}
