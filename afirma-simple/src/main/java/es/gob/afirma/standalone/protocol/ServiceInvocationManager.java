/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.protocol;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.swing.Timer;

import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.keystores.mozilla.apple.AppleScript;
import es.gob.afirma.standalone.AutoFirmaUtil;

/** Gestor de la invocaci&oacute;n por <i>socket</i>. */
public final class ServiceInvocationManager {

	static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$


	/** Tiempo de espera de cada <i>socket</i> en milisegundos. */
	private static int SOCKET_TIMEOUT = 90000;

	/** Par&aacute;metro de entrada con la versi&oacute;n del protocolo que se va a utilizar. */
	private static final String PROTOCOL_VERSION_PARAM = "v"; //$NON-NLS-1$

	/** Versi&oacute;n de protocolo m&aacute;s avanzada soportada. */
	private static final int CURRENT_PROTOCOL_VERSION = 1;

	/** Listado de versiones de protocolo soportadas. */
	private static final int[] SUPPORTED_PROTOCOL_VERSIONS = new int[] { CURRENT_PROTOCOL_VERSION };

	private static final String IDSESSION = "idsession"; //$NON-NLS-1$

	// parametros para carga del certificado SSL
	private static final String KSPASS = "654321"; //$NON-NLS-1$
	private static final String CTPASS = "654321"; //$NON-NLS-1$
	private static final String KEYSTORE_NAME = "autofirma.pfx"; //$NON-NLS-1$
	private static final String PKCS12 = "PKCS12"; //$NON-NLS-1$
	private static final String KEY_MANAGER_TYPE = "SunX509"; //$NON-NLS-1$
	private static final String SSLCONTEXT = "TLSv1"; //$NON-NLS-1$

	/** Coge el foco del sistema en macOS. En el resto de sistemas no hace nada. */
	public static void focusApplication() {
		if (Platform.OS.MACOSX.equals(Platform.getOS())) {
			final String scriptCode = "tell me to activate"; //$NON-NLS-1$
			final AppleScript script = new AppleScript(scriptCode);
			try {
				script.run();
			}
			catch (final Exception e) {
				LOGGER.warning("Fallo cogiendo el foco en macOS: " + e); //$NON-NLS-1$
			}
		}
	}

	/** Constructor vac&iacute;o privado para que no se pueda instanciar la clase ya que es est&aacute;tico. */
	private ServiceInvocationManager(){
		// No instanciable
	}

	/** Inicia el servicio. Se intenta establecer un <code>socket</code> que escuche en el puerto pasado por la URL.
	 * @param url URL (debe indicarse el puerto).
	 * @throws UnsupportedProtocolException Si no se soporta el protocolo o la versi&oacute;n de este. */
	static void startService(final String url) throws UnsupportedProtocolException {

		checkSupportProtocol(getVersion(url));

		try {
			// ruta de la que debe buscar el fichero
			final File sslKeyStoreFile = getKeyStoreFile();
			if (sslKeyStoreFile == null) {
				throw new KeyStoreException(
					"No se encuentra el almacen para el cifrado de la comunicacion SSL" //$NON-NLS-1$
				);
			}

			LOGGER.info("Se utilizara el siguiente almacen para establecer el socket SSL: " + sslKeyStoreFile.getAbsolutePath()); //$NON-NLS-1$

			// pass del fichero
			final char ksPass[] = KSPASS.toCharArray();
			final char ctPass[] = CTPASS.toCharArray();
			// generamos el key store desde el fichero del certificado, de tipo PKCS12
			final KeyStore ks = KeyStore.getInstance(PKCS12);
			ks.load(new FileInputStream(sslKeyStoreFile), ksPass);
			// key manager factory de tipo SunX509
			final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KEY_MANAGER_TYPE);
			kmf.init(ks, ctPass);

			final SSLContext sc = SSLContext.getInstance(SSLCONTEXT);
			sc.init(kmf.getKeyManagers(), null, null);
			LOGGER.info("Iniciando servicio local de firma: " + url); //$NON-NLS-1$
			final SSLServerSocketFactory ssocketFactory = sc.getServerSocketFactory();

			final ChannelInfo channelInfo = getChannelInfo(url);
			final SSLServerSocket ssocket = tryPorts(channelInfo.getPorts(), ssocketFactory);
			ssocket.setReuseAddress(true);

			// empieza la cuenta atras del temporizador.

			/** Temporizador para cerrar la aplicaci&oacute;n cuando pase un tiempo de inactividad. */
			final Timer timer = new Timer(SOCKET_TIMEOUT, evt -> {
				LOGGER.warning("Se ha caducado la conexion. Se deja de escuchar en el puerto..."); //$NON-NLS-1$
				if (Platform.OS.MACOSX.equals(Platform.getOS())) {
					closeMacService(channelInfo.getIdSession());
				}
				System.exit(-4);
			});
			timer.start();

			while (true){
				try {
					new CommandProcessorThread(ssocket.accept(), timer, channelInfo.getIdSession()).start();
				}
				catch (final SocketTimeoutException e) {
					LOGGER.severe("Tiempo de espera del socket terminado: " + e); //$NON-NLS-1$
				}
			}
		}

		// Con las excepciones no hacemos nada ya que no tenemos forma de transmitir el
		// error de vuelta y no debemos mostrar dialogos graficos
		catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "Error en la comunicacion a traves del socket", e); //$NON-NLS-1$
		}
		catch(final KeyStoreException e){
            LOGGER.severe("Error con el keyStore: " + e); //$NON-NLS-1$
		}
        catch(final NoSuchAlgorithmException e){
            LOGGER.severe("Error con el algoritmo del  certificado: " + e); //$NON-NLS-1$
        }
        catch(final CertificateException e){
            LOGGER.severe("Error con el certificado: " + e); //$NON-NLS-1$
        }
        catch(final UnrecoverableKeyException e){
            LOGGER.severe("Error al recuperar la key: " + e); //$NON-NLS-1$
        }
        catch(final KeyManagementException e){
            LOGGER.severe("Error con el KeyManager: " + e); //$NON-NLS-1$
        }

	}

	/** Obtiene el fichero del almac&eacute;n con la clave SSL de alguno de los directorios
	 * del sistema en los que puede estar.
	 * @return Almac&eacute;n de claves o {@code null} si no se encontr&oacute;. */
	private static File getKeyStoreFile() {

		File appDir = AutoFirmaUtil.getApplicationDirectory();

		if (appDir != null && new File(appDir, KEYSTORE_NAME).exists()) {
			return new File(appDir, KEYSTORE_NAME);
		}

		if (Platform.getOS() == Platform.OS.WINDOWS) {
			appDir = AutoFirmaUtil.getWindowsAlternativeAppDir();
			if (appDir != null && new File(appDir, KEYSTORE_NAME).exists()) {
				return new File(appDir, KEYSTORE_NAME);
			}
		}
		else if (Platform.getOS() == Platform.OS.LINUX) {
			appDir = AutoFirmaUtil.getLinuxAlternativeAppDir();
			if (appDir != null && new File(appDir, KEYSTORE_NAME).exists()) {
				return new File(appDir, KEYSTORE_NAME);
			}
		}
		else if (Platform.getOS() == Platform.OS.MACOSX) {
			appDir = AutoFirmaUtil.getMacOsXAlternativeAppDir();
			if (new File(appDir, KEYSTORE_NAME).exists()) {
				return new File(appDir, KEYSTORE_NAME);
			}
		}

		return null;
	}

	/** Obtiene los puertos que se deben probar para la conexi&oacute;n externa.
	 * Asigna cual es la clave.
	 * @param url URL de la que extraer los puertos.
	 * @return Listados de puertos. */
	private static ChannelInfo getChannelInfo(final String url) {
		final URI u;
		try {
			u = new URI(url);
		}
		catch (final Exception e) {
			throw new IllegalArgumentException("La URI (" + url + ") de invocacion no es valida: " + e); //$NON-NLS-1$ //$NON-NLS-2$
		}
		final String query = u.getQuery();
		checkNullParameter(query, "La URI de invocacion no contiene parametros: " + url); //$NON-NLS-1$
		final Properties p = new Properties();
		try {
			p.load(new ByteArrayInputStream(query.replace("&", "\n").getBytes())); //$NON-NLS-1$ //$NON-NLS-2$
		}
		catch (final IOException e) {
			throw new IllegalArgumentException(
				"Los parametros de la URI de invocacion no estan el el formato correcto: " + url, //$NON-NLS-1$
				e);
		}
		final String ps = p.getProperty("ports"); //$NON-NLS-1$
		checkNullParameter(ps, "La URI de invocacion no contiene el parametro 'ports': " + url); //$NON-NLS-1$
		final String[] portsText = ps.split(","); //$NON-NLS-1$
		final int[] ports = new int[portsText.length];
		for (int i=0; i<portsText.length; i++) {
			try {
				ports[i] = Integer.parseInt(portsText[i]);
			}
			catch(final Exception e) {
				throw new IllegalArgumentException(
					"El parametro 'ports' de la URI de invocacion contiene valores no numericos: " + e //$NON-NLS-1$
				, e);
			}
		}
		final String idSession = p.getProperty(IDSESSION);
		if(idSession != null ){
		    LOGGER.info("Se ha recibido un idSesion para la transaccion: " + idSession); //$NON-NLS-1$
		}
		else {
            LOGGER.info("No se utilizara idSesion durante la transaccion"); //$NON-NLS-1$
        }

		return new ChannelInfo(idSession, ports);
	}

	/** Obtiene el par&aacute;metro de versi&oacute;n declarado en la URL.
	 * @param url URL de la que extraer la versi&oacute;n.
	 * @return Valor del par&aacute;metro de versi&oacute;n ('v') o {@code null} si no est&aacute; definido. */
	private static String getVersion(final String url) {

		final URI u;
		try {
			u = new URI(url);
		}
		catch (final Exception e) {
			throw new IllegalArgumentException("La URI " + url + "de invocacion no es valida: " + e); //$NON-NLS-1$ //$NON-NLS-2$
		}
		final String query = u.getQuery();
		checkNullParameter(query, "La URI de invocacion no contiene parametros: " + url); //$NON-NLS-1$
		final Properties p = new Properties();
		try {
			p.load(new ByteArrayInputStream(query.replace("&", "\n").getBytes())); //$NON-NLS-1$ //$NON-NLS-2$
		}
		catch (final IOException e) {
			throw new IllegalArgumentException(
				"Los parametros de la URI de invocacion no estan el el formato correcto: " + url //$NON-NLS-1$
			, e);
		}
		return p.getProperty(PROTOCOL_VERSION_PARAM);
	}

	/** Intenta realizar una conexi&oacute; por <i>socket</i> en los puertos que se pasan por par&aacute;metro.
	 * @param ports Puertos a probar.
	 * @param socket <i>Socket</i> que se intenta conectar.
	 * @return El <code>SSLServerSocket</code> ya creado en el primer puerto encontrado disponible.
	 * @throws IOException Si ocurren errores durante el intento. */
	private static SSLServerSocket tryPorts(final int[] ports, final SSLServerSocketFactory socket) throws IOException {
		checkNullParameter(ports, "La lista de puertos no puede ser nula"); //$NON-NLS-1$
		checkNullParameter(socket, "El socket servidor no puede ser nulo"); //$NON-NLS-1$
		for (final int port : ports) {
			try {
				final SSLServerSocket ssocket = (SSLServerSocket) socket.createServerSocket(port);
				LOGGER.info("Establecido el puerto " + port + " para el servicio Cliente @firma"); //$NON-NLS-1$ //$NON-NLS-2$
				return ssocket;
			}
			catch (final BindException e) {
				LOGGER.warning(
					"El puerto " + port + " parece estar en uso, se continua con el siguiente: " + e //$NON-NLS-1$ //$NON-NLS-2$
				);
			}
			catch(final Exception e) {
				LOGGER.warning(
					"No se ha podido conectar al puerto " + port + ", se intentara con el siguiente: " + e //$NON-NLS-1$ //$NON-NLS-2$
				);
			}
		}
		throw new IOException("No se ha podido ligar el socket servidor a ningun puerto"); //$NON-NLS-1$
	}

	/** Comprueba que un par&aacute;metro no sea nulo.
	 * @param parameter Par&aacute;metro que se debe comprobar que no sea nulo.
	 * @param excepcionText Texto que se debe lanzar con la excepci&oacute;n. */
	private static void checkNullParameter (final Object parameter, final String excepcionText){
		if (parameter == null) {
			throw new IllegalArgumentException(excepcionText);
		}
	}

	/** Comprueba si una versi&oacute;n de protocolo est&aacute; soportado por la implementaci&oacute;n actual.
	 * @param protocolId Identificador de la versi&oacute;n del protocolo.
	 * @throws UnsupportedProtocolException Cuando la versi&oacute;n de protocolo utilizada no se encuentra
	 *                                      entre las soportadas. */
	private static void checkSupportProtocol(final String protocolId) throws UnsupportedProtocolException {
		int protocolVersion = 1;
		if (protocolId != null) {
			try {
				protocolVersion = Integer.parseInt(protocolId.trim());
			}
			catch (final Exception e) {
				LOGGER.info(
					"El ID de protocolo indicado no es un numero entero (" + protocolId + "): " + e //$NON-NLS-1$ //$NON-NLS-2$
				);
				protocolVersion = -1;
			}
		}

		for (final int version : SUPPORTED_PROTOCOL_VERSIONS) {
			if (version == protocolVersion) {
				return;
			}
		}

		throw new UnsupportedProtocolException(protocolVersion, protocolVersion > CURRENT_PROTOCOL_VERSION);
	}


	/** Mata el proceso de AutoFirma cuando estamos en macOS. En el resto de sistemas
	 * no hace nada.
	 * @param idSession Identificador de sesi&oacute;n utilizado para identificar al cliente. */
	static void closeMacService(String idSession) {
		LOGGER.warning("Ejecuto kill"); //$NON-NLS-1$
		final AppleScript script = new AppleScript(
				"kill -9 $(ps -ef | grep " + idSession + " | awk '{print $2}')"  //$NON-NLS-1$ //$NON-NLS-2$
				);
		try {
			script.run();
		}
		catch (final Exception e) {
			LOGGER.warning("No se ha podido cerrar la aplicacion: " + e); //$NON-NLS-1$
		}
	}

	private static class ChannelInfo {

		private final String idSession;
		private final int[] ports;

		public ChannelInfo(String idSession, int[] ports) {
			this.idSession = idSession;
			this.ports = ports;
		}

		public String getIdSession() {
			return this.idSession;
		}

		public int[] getPorts() {
			return this.ports;
		}
	}
}
