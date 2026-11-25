package dev.opeope.power.mera;

/** Las órdenes propias de la Mera Mera no Mi (el cliente las manda con Payloads.Action). */
public final class MeraActions {
	private MeraActions() {}

	public static final int HIKEN_START = 130, HIKEN_RELEASE = 131, ENTEI_START = 132, ENTEI_RELEASE = 133,
		KAGERO_START = 134, KAGERO_END = 135, DASH = 136, HIKYAKU = 137, HIGAN = 138, JUJIKA = 139, HOTARUBI = 140,
		HIDARUMA = 141, KYOKAEN = 142, ENJOMO = 143, SHIRANUI = 144, HIBASHIRA = 145, BODY = 146;

	/** Las que se mandan al soltar una tecla: valen aunque la fruta no se pueda usar ahora (bajo el agua). */
	public static boolean isRelease(int action) {
		return action == HIKEN_RELEASE || action == ENTEI_RELEASE || action == KAGERO_END;
	}
}
