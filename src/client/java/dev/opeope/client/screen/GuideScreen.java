package dev.opeope.client.screen;

import dev.opeope.client.input.Keys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** H: el libro de apartados, como en el juego. Con la rueda se baja. */
public class GuideScreen extends Screen {
	private final List<Object[]> rows = new ArrayList<>();   // {texto, color}
	private double scroll;

	public GuideScreen() {
		super(Text.literal("Ope Ope no Mi"));
	}

	private static String k(Keys key) {
		return key.label().toUpperCase();
	}

	private void title(String s) {
		rows.add(new Object[]{"", 0});
		rows.add(new Object[]{s, 0xFF8FD8FF});
	}

	private void row(String keys, String what) {
		rows.add(new Object[]{"  " + keys + "  —  " + what, 0xFFFFFFFF});
	}

	private void note(String s) {
		rows.add(new Object[]{"  " + s, 0xFF9AA7B0});
	}

	@Override
	protected void init() {
		rows.clear();
		String tab = k(Keys.ROW2), r = k(Keys.TAKT);
		note("La fruta es siempre tuya: funciona con la mano vacía (Fruta), con la Kikoku (Espada) o con cualquier cosa en la mano.");
		note("Agachado, los clics vuelven a ser los de Minecraft. En el agua la fruta no funciona y te hundes.");
		note("Las teclas se cambian en config/opeope-teclas.json.");
		title("ROOM");
		row(k(Keys.ROOM) + " mantener", "abrirlo desde el cuerpo: crece cada vez más rápido, hasta 160 bloques; al soltar se fija");
		row(k(Keys.ROOM) + " + clic", "lanzarlo: vuela a la mira y florece allí con su tamaño");
		row(k(Keys.ROOM) + " + rueda", "agrandar o encoger");
		row(k(Keys.ROOM) + " + clic der.", "traerlo hasta ti");
		row(k(Keys.ROOM) + " toque", "quitarlo");
		note("Desde fuera también funciona: si miras dentro, Amputate, Shambles, Takt y el suelo actúan dentro.");
		title("Cortes (mano vacía dentro del ROOM)");
		row("Clic", "Amputate: con el clic pulsado la cámara se queda quieta; arrastra el ratón y trazas el tajo (una línea marca lo que vas a cortar); al soltar, corta. Un clic sin arrastrar repite el último tajo");
		row("Clic der. mantener", "Bisturí: el tiempo va al 12 % (un jugador); el plano pasa por donde apuntas");
		row("  rueda / Mayús / Ctrl", "girar, ladear o mover el plano · clic: corta ya (hasta 16); lo cortado se queda quieto y cae todo al soltar");
		row(tab + " 1", "patrón: un tajo, rodajas, cruz o dados");
		row(tab + " 2", "alcance: todo lo que cruza el ROOM o solo el objetivo");
		row(tab + " 3", "suspender: lo cortado queda flotando hasta que lo pulses otra vez");
		note("Lo que queda encima del corte resbala por el plano hacia donde fue el tajo; el borde del ROOM también corta. Si está encajado, se separa y flota unos segundos (agárralo con Takt). Un mob cortado no pierde vida: queda en piezas vivas.");
		note("Con la Kikoku dentro del ROOM, cada tajo de la espada corta de verdad lo que alcanza el filo.");
		title("Shambles");
		row(k(Keys.SHAMBLES), "con el aire: apareces donde apuntas dentro del ROOM");
		row(k(Keys.SHAMBLES) + " " + k(Keys.SHAMBLES), "marca algo; luego lo mismo (te cambias), otro objetivo (se cambian) o un punto vacío");
		row(k(Keys.SHAMBLES) + " agachado", "cambia bloques de sitio (marca uno y luego el otro)");
		row(k(Keys.MARKER), "piedra marcador: lánzala y haz Shambles con ella");
		row(k(Keys.SCAN) + " y " + k(Keys.SHAMBLES), "Scan y luego robar lo que lleva en la mano");
		title("Takt");
		row(r + " toque", "coger lo que miras (seres, objetos, piezas o un trozo de suelo); otro toque, otro más");
		row(r + " " + r, "levantar todo lo suelto alrededor del punto");
		row("Clic / mantener", "lanzar el de delante / ráfaga");
		row("Clic der.", "mantener y soltar: lo lanza todo junto");
		row(r + " mantener +", k(Keys.R_ORBIT) + " órbita · " + k(Keys.R_PARK) + " aparcar · " + k(Keys.R_SETDOWN) + " posar · " + k(Keys.R_DROP) + " soltar · rueda distancia");
		row(r + " mantener +", k(Keys.R_ZONE) + " zona · " + k(Keys.R_CUT) + " lo cortado · " + k(Keys.R_GATHER) + " rejuntar · " + k(Keys.R_UNITE) + " unir todo · clic der. lanzar todo");
		title("Armas del entorno (con material en Takt)");
		row(r + " + 1", "lanza: atraviesa y ensarta al primero; " + r + " sobre ella la recupera");
		row(r + " + 2", "hoja colosal: un tajo de lado a lado; dentro del ROOM corta como Amputate");
		row(r + " + 3", "martillo: lo alzas y lo descargas; la onda tumba a todos");
		row(r + " + 4", "estacas: suben en corona y llueven; la mitad busca a los enemigos");
		row(r + " + 5", "muralla: queda de pie delante y para las flechas; clic, empujón");
		title("Unión");
		row(tab + " " + k(Keys.T_UNION) + " toque", "modo Unión: clic en una pieza y clic en el destino, vuela y queda cosida");
		row("  clic der.", "reconstruir: la pieza vuelve a su sitio y se sella");
		row(tab + " " + k(Keys.T_UNION) + " mantener", "Cirugía: te curas o curas a quien miras, y recompone lo cortado");
		row(tab + " " + k(Keys.T_UNION) + " con Takt", "lo que llevas se cose a lo que toca");
		title("El suelo del ROOM");
		row(tab + " " + k(Keys.T_PILLAR), "pilar (mantener: sigue subiendo y te sube)");
		row(tab + " " + k(Keys.T_WALL), "muralla (mantener: sigue tu mirada como una ola)");
		row(tab + " " + k(Keys.T_CHASM), "sima (mantener: una zanja que sigue tu mirada)");
		row(tab + " " + k(Keys.T_PLATEAU), "meseta (alza el suelo con todo lo que tiene encima)");
		title("Técnicas");
		row(k(Keys.RADIO_KNIFE), "Radio Knife: corte eléctrico; lo cortado no se rearma en 5 s");
		row(k(Keys.INJECTION), "Injection Shot: embestida que dispara al clavarse");
		row(k(Keys.GAMMA), "Gamma Knife: destruye por dentro, no mira la armadura");
		row(k(Keys.SCAN), "Scan: ves a todos a través de las paredes y lo que llevan");
		row(k(Keys.CURTAIN), "Curtain: un velo delante que reduce el daño");
		row(tab + " " + k(Keys.T_MES), "Mes: le sacas el corazón; " + k(Keys.RADIO_KNIFE) + " o clic der. lo aprieta; " + tab + " " + k(Keys.T_MES) + " lo devuelve o lo suelta");
		row(tab + " " + k(Keys.T_COUNTER), "Counter Shock: descarga con las palmas");
		row(tab + " " + k(Keys.T_STERBEN), "Sterben: la nodachi gira como una sierra");
		row(tab + " " + k(Keys.T_SOULS), "Cambio de almas: marca a uno y luego a otro");
		row(tab + " " + k(Keys.T_RAIN), "Lluvia: llueven cortes sobre todo lo que vive en el ROOM");
		title("Despertar");
		note("Se gana venciendo al Wither o al Dragón del End con la fruta. " + k(Keys.AWAKEN) + " lo enciende y lo apaga.");
		row(k(Keys.RADIO_KNIFE), "K-ROOM");
		row(k(Keys.INJECTION) + " mantener", "Anesthesia");
		row(k(Keys.GAMMA), "Shock Wille");
		row(k(Keys.SCAN) + " mantener", "R-ROOM: crece donde miras y al soltar estalla en cortes");
		row(tab + " " + k(Keys.T_COUNTER), "Puncture Wille");
		row(k(Keys.CURTAIN), "Silent");
		title("Conseguir la fruta");
		note("A veces la suelta el Guardián Anciano o el Warden. En creativo, en la pestaña Ope Ope no Mi. Con trucos: /opeope fruta.");
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, scroll - verticalAmount * 24);
		return true;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		int margin = 20;
		int maxW = this.width - margin * 2;
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Ope Ope no Mi · libro de apartados"), this.width / 2, 8, 0xFF8FD8FF);
		int y = 26 - (int) scroll;
		ctx.enableScissor(0, 22, this.width, this.height);
		for (Object[] row : rows) {
			String s = (String) row[0];
			int color = (int) row[1];
			if (s.isEmpty()) {
				y += 6;
				continue;
			}
			for (OrderedText line : this.textRenderer.wrapLines(Text.literal(s), maxW)) {
				if (y > 10 && y < this.height) ctx.drawTextWithShadow(this.textRenderer, line, margin, y, color);
				y += 10;
			}
		}
		ctx.disableScissor();
		double maxScroll = Math.max(0, y + scroll - this.height + 20);
		if (scroll > maxScroll) scroll = maxScroll;
	}

	@Override
	public boolean shouldPause() {
		return true;
	}
}
