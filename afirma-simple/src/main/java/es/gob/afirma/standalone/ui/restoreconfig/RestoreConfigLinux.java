package es.gob.afirma.standalone.ui.restoreconfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.swing.JOptionPane;
import javax.swing.JTextArea;

import es.gob.afirma.standalone.SimpleAfirmaMessages;
import es.gob.afirma.standalone.ui.restoreconfig.CertUtil.CertPack;
import es.gob.afirma.standalone.ui.restoreconfig.RestoreConfigFirefox.MozillaProfileNotFoundException;

/** Configura la instalaci&oacute;n en Linux para la correcta ejecuci&oacute;n de AutoFirma. */
final class RestoreConfigLinux implements RestoreConfig {

    static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

    private static final String KS_FILENAME = "autofirma.pfx"; //$NON-NLS-1$
    private static final String FILE_AUTOFIRMA_CERTIFICATE = "AutoFirma_ROOT.cer"; //$NON-NLS-1$
    private static final String KS_PASSWORD = "654321"; //$NON-NLS-1$
    private static final String LINUX_PROTOCOL_SCRIPT_NAME = "AutoFirma.js"; //$NON-NLS-1$
    static final String EXPORT_PATH = "export PATH=$PATH:"; //$NON-NLS-1$
    static final String EXPORT_LD_LIBRARY ="export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:"; //$NON-NLS-1$
    
    /**
     * Caracter de salto de l&iacute;nea para los mensajes de la consola de restauraci&oacute;n
     */
    private String newline = System.getProperty("line.separator");

	/* (non-Javadoc)
	 * @see es.gob.afirma.standalone.ui.restoreconfig.RestoreConfig#restore(javax.swing.JTextArea)
	 */
	@Override
	public void restore(JTextArea taskOutput) throws IOException, GeneralSecurityException {
		
		appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.2"));

		final File appDir = RestoreConfigUtil.getApplicationDirectory();

		appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.3") + appDir.getAbsolutePath()); //$NON-NLS-1$

		if (!checkSSLKeyStoreGenerated(appDir) || !checkSSLRootCertificateGenerated(appDir)) {
			appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.5")); //$NON-NLS-1$

			// Si al menos nos falta uno de los certificados, elimino ambos para
			// volver a generarlos
			deleteInstalledCertificates(appDir);

			final CertPack certPack = CertUtil.getCertPackForLocalhostSsl(RestoreConfigUtil.CERT_ALIAS, KS_PASSWORD);

			appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.11")); //$NON-NLS-1$

			// Generacion del certificado pfx
			RestoreConfigUtil.installFile(certPack.getPkcs12(),
					new File(RestoreConfigUtil.getApplicationDirectory(), KS_FILENAME));

			// Generacion del certificado raiz .cer
			RestoreConfigUtil.installFile(certPack.getCaCertificate().getEncoded(),
					new File(RestoreConfigUtil.getApplicationDirectory(), FILE_AUTOFIRMA_CERTIFICATE));

		} else {
			appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.14")); //$NON-NLS-1$
		}

		// comando para sacar los usuarios del sistema
		final String[] command = new String[] { "cut", //$NON-NLS-1$
				"-d:", //$NON-NLS-1$
				"-f6", //$NON-NLS-1$
				"/etc/passwd" //$NON-NLS-1$
		};

		try {
			LOGGER.info("Voy a instalar el certificado CA raiz en Mozilla y Chrome"); //$NON-NLS-1$
			appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.13")); //$NON-NLS-1$
			RestoreConfigFirefox.removeAppExecutionWarningInChrome(appDir, command);

			closeChrome();

			RestoreConfigFirefox.installRootCAChromeKeyStore(appDir, command);

			// Cerramos el almacen de firefox si esta abierto
			closeFirefox();

			RestoreConfigFirefox.installRootCAMozillaKeyStore(appDir, command);
		} catch (final MozillaProfileNotFoundException e) {
			appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.12")); //$NON-NLS-1$
			// LOGGER.warning(Messages.getString("RestoreConfigLinux.12"));
			// //$NON-NLS-1$
		}
		
		// Restauracion del fichero de configuracion de protocolo afirma en Linux
		restoreProtocolHandler();

		appendMessage(taskOutput, SimpleAfirmaMessages.getString("RestoreConfigLinux.8")); //$NON-NLS-1$

	}

    /** Comprueba si ya existe un almac&eacute;n de certificados generado.
     * @param appConfigDir Directorio de configuraci&oacute;n de la aplicaci&oacute;n.
     * @return {@code true} si ya existe un almac&eacute;n de certificados SSL, {@code false} en caso contrario. */
    private static boolean checkSSLKeyStoreGenerated(final File appConfigDir) {
        return new File(appConfigDir, KS_FILENAME).exists();
    }
    
    /** Comprueba si ya existe un certificado ra&iacute;z generado.
	 * @param appDir Directorio de la aplicaci&oacute;n.
	 * @return {@code true} si ya existe un certificado ra&iacute;z .cer, {@code false} en caso contrario. */
	private static boolean checkSSLRootCertificateGenerated(final File appDir) {
		return new File(appDir, FILE_AUTOFIRMA_CERTIFICATE).exists();
	}
   
	/**
	 * Concatena un texto a una nueva l&iacute;nea al par&aacute;metro JTextArea
	 * @param taskOutput JTextArea donde el texto es concatenado
	 * @param message Texto a concatenar.
	 */
	private void appendMessage(JTextArea taskOutput, String message) {
		taskOutput.append(message + newline);
		taskOutput.setCaretPosition(taskOutput.getDocument().getLength());
	}
	
	/**
	 * Elimina los ficheros de certificado ra&iacutez y almac&eacute;n SSL del disco
	 * como paso previo a volver a generarlos
	 * @param appDir Ruta del directorio de la aplicaci&oacute;n
	 * @throws IOException
	 */
	private void deleteInstalledCertificates(File appDir) throws IOException {

		if (checkSSLKeyStoreGenerated(appDir)) {
			
			File sslKey = new File(appDir, KS_FILENAME);
			
			if (!sslKey.delete()) {
				throw new IOException("No puedo eliminar autofirma.pfx");
			}
			
		}

		if (checkSSLRootCertificateGenerated(appDir)) {

			File sslRoot = new File(appDir, FILE_AUTOFIRMA_CERTIFICATE);
			
			if (!sslRoot.delete()) {
				throw new IOException("No puedo eliminar AutoFirma_ROOT.cer");
			}

		}

	}
	
	/**
	 * Pide al usuario que cierre el navegador Mozilla Firefox y no permite continuar hasta que lo hace.
	 */
	private static void closeFirefox() {

		while (isProcessRunningLinux("/usr/lib/firefox/firefox")) {
			JOptionPane.showMessageDialog(
					null,
					SimpleAfirmaMessages.getString("RestoreAutoFirma.7"), //$NON-NLS-1$
					SimpleAfirmaMessages.getString("RestoreAutoFirma.9"), //$NON-NLS-1$
					JOptionPane.WARNING_MESSAGE);
		}
	}
	
	/**
	 * Pide al usuario que cierre el navegador Mozilla Firefox y no permite continuar hasta que lo hace.
	 */
	private static void closeChrome() {

		while (isProcessRunningLinux("/opt/google/chrome/chrome")) {
			JOptionPane.showMessageDialog(
					null,
					SimpleAfirmaMessages.getString("RestoreAutoFirma.8"), //$NON-NLS-1$
					SimpleAfirmaMessages.getString("RestoreAutoFirma.9"), //$NON-NLS-1$
					JOptionPane.WARNING_MESSAGE);
		}
	}
	
	/**
	 * Determina si un proceso est&aacute; corriendo en Linux
	 * @param process Nombre del proceso a buscar mediante comando ps
	 * @return {@code true} si el proceso est&aacute; corriendo {@code false} en caso contrario.
	 */
	private static Boolean isProcessRunningLinux(final String process) {
		
		String line;
		String pidInfo ="";
		Boolean isRunning = Boolean.FALSE;
		
		Process p;
		try {
															
			String[] commands = { "/bin/bash", "-c", "ps -aux"};
			p = Runtime.getRuntime().exec(commands);
			
			p.waitFor();
					
			BufferedReader input =  new BufferedReader(new InputStreamReader(p.getInputStream()));

			while ((line = input.readLine()) != null) {
			    pidInfo+=line; 
			}
						
			input.close();
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			LOGGER.severe("Error al detectar si el proceso " + process + " esta activo");
		} catch (InterruptedException ie) {
			
		}

		if(pidInfo.contains(process))
		{
		    isRunning = Boolean.TRUE;
		}
		
		return isRunning;
	}
	
	
	/**
	 * Restaura la configuraci&oacute;n del protocolo afirma en Linux
	 * @return
	 */
	private static Boolean restoreProtocolHandler() throws IOException {
		
		final StringBuilder sb = new StringBuilder();

		sb.append("pref(\"network.protocol-handler.app.afirma\",\"/usr/bin/AutoFirma\");");
		sb.append(" ");
		sb.append("pref(\"network.protocol-handler.warn-external.afirma\",false);");
		sb.append(" ");
		sb.append("pref(\"network.protocol-handler.external.afirma\",true);");
		sb.append("\n"); //$NON-NLS-1$

		// Obtenemos la ruta de los scripts
		String path = new File(new File("/etc/firefox/pref"), LINUX_PROTOCOL_SCRIPT_NAME).getAbsolutePath();
		final File protocolScript = new File(path);

		try {

			if (new File(new File("/etc/firefox/pref"), LINUX_PROTOCOL_SCRIPT_NAME).exists()) {

				File afirmaProtocol = new File(new File("/etc/firefox/pref"), LINUX_PROTOCOL_SCRIPT_NAME);

				if (!afirmaProtocol.delete()) {
					throw new IOException("No puedo eliminar AutoFirma.js");
				}
			}

			final FileOutputStream fout = new FileOutputStream(protocolScript, true);
			fout.write(sb.toString().getBytes());
			fout.close();

		} catch (IOException e) {
			LOGGER.severe("Excepcion en la creacion del script linux para la configuracion del protocolo afirma: " + e //$NON-NLS-1$
			);
			return true;
		}

		return false;
	}
}