package es.gob.afirma.keystores.dnie;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.x500.X500Principal;

import es.gob.afirma.core.InvalidOSException;
import es.gob.afirma.core.MissingLibraryException;
import es.gob.afirma.keystores.main.common.AOKeyStore;
import es.gob.afirma.keystores.main.common.AOKeyStoreManager;
import es.gob.afirma.keystores.main.common.AOKeyStoreManagerException;
import es.gob.afirma.keystores.main.common.AOKeyStoreManagerFactory;
import es.gob.afirma.keystores.main.common.AOKeystoreAlternativeException;

/** Representa a un <i>AOKeyStoreManager</i> para acceso a almacenes de claves de DNIe mediante controlador
 * 100% Java m&aacute;s un segundo almac&eacute;n en el que los certificados de ambos se tratan de forma unificada
 * y homogenea.
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s */
public class DnieUnifiedKeyStoreManager extends AOKeyStoreManager {

	private static X509Certificate dnieRootCertificate;

	static {
		try {
			dnieRootCertificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(ClassLoader.getSystemResourceAsStream("ACRAIZ-SHA2.crt")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		catch (final CertificateException e) {
			Logger.getLogger("es.gob.afirma").warning( //$NON-NLS-1$
				"No se ha podido cargal el certificado raiz del DNIe, la cadena de confianza puede estar incompleta: " + e //$NON-NLS-1$
			);
			dnieRootCertificate = null;
		}
	}
	private static final List<String> DNIE_ALIASES = new ArrayList<String>(2);

	private final String[] aliases;

	static {
		DNIE_ALIASES.add("CertAutenticacion"); //$NON-NLS-1$
		DNIE_ALIASES.add("CertFirmaDigital"); //$NON-NLS-1$
	}

	private static final X500Principal DNIE_ISSUER = new X500Principal("CN=AC DNIE 001, OU=DNIE, O=DIRECCION GENERAL DE LA POLICIA, C=ES"); //$NON-NLS-1$

	private final AOKeyStoreManager ORIGINAL_KSM;
	private AOKeyStoreManager DNIE_KSM = null;

	/** Crea un almc&eacute;n de claves en base a un agregado del DNIe con controlador 100% Java y un segundo almac&eacute;n
	 * indicado como par&aacute;metro.
	 * @param originalKsm ALmac&eacute;n de claves original
	 * @param parent Componente padre para la modalidad
	 * @throws MissingLibraryException Si el entorno de ejecuci&oacute;n carece de alguna de las bibliotecas necesarias
	 * @throws InvalidOSException Si el sistema operativo no soporta alguno de los almacenes
	 * @throws AOKeystoreAlternativeException Si no se puede inicializar el almac&eacute;n pero existe una alternativa de uso
	 * @throws IOException Si se producen errores de entrada-salida en la inicializaci&oacute;n de los almacenes */
	public DnieUnifiedKeyStoreManager(final AOKeyStoreManager originalKsm, final Object parent) throws MissingLibraryException, InvalidOSException, AOKeystoreAlternativeException, IOException {
		if (originalKsm == null) {
			throw new IllegalArgumentException("Es necesario un almacen al que anadir los certificados de DNIe, no puede ser nulo"); //$NON-NLS-1$
		}

		this.ORIGINAL_KSM = originalKsm;

		boolean dnieNeeded = true;

		for (final String alias : originalKsm.getAliases()) {
			if (originalKsm.getCertificate(alias).getIssuerX500Principal().equals(DNIE_ISSUER)) {
				dnieNeeded = false;
				break;
			}
		}
		if (dnieNeeded) {
			try {
				this.DNIE_KSM = AOKeyStoreManagerFactory.getAOKeyStoreManager(
					AOKeyStore.DNIEJAVA,
					null, // Lib
					originalKsm.getType() + "_PLUS_DNIE", // Description //$NON-NLS-1$
					null, // PasswordCallback
					parent
				);
			}
			catch(final Exception e) {
				Logger.getLogger("es.gob.afirma").info("No se puede usar DNIe con controlador 100% Java: " + e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		// Unificamos los alias
		final String[] originalAliases = originalKsm.getAliases();
		this.aliases = new String[originalAliases.length + ((this.DNIE_KSM != null) ? 2 : 0)];
		System.arraycopy(originalAliases, 0, this.aliases, 0, originalAliases.length);
		if (this.DNIE_KSM != null) {
			this.aliases[this.aliases.length-1] = DNIE_ALIASES.get(0);
			this.aliases[this.aliases.length-2] = DNIE_ALIASES.get(1);
		}
	}

	/** {@inheritDoc} */
	@Override
	public String[] getAliases() {
		return this.aliases;
	}

	/** {@inheritDoc} */
	@Override
	public X509Certificate getCertificate(final String alias) {
		if (!DNIE_ALIASES.contains(alias) || this.DNIE_KSM == null) {
			return this.ORIGINAL_KSM.getCertificate(alias);
		}
		return this.DNIE_KSM.getCertificate(alias);
	}

	/** {@inheritDoc} */
	@Override
	public X509Certificate[] getCertificateChain(final String alias) {
		if (!DNIE_ALIASES.contains(alias) || this.DNIE_KSM == null) {
			return this.ORIGINAL_KSM.getCertificateChain(alias);
		}
		final X509Certificate[] chain = new X509Certificate[3];
		System.out.println(this.DNIE_KSM.getCertificateChain(alias)[0].getClass().getName());
		chain[0] = this.DNIE_KSM.getCertificateChain(alias)[0];
		chain[1] = this.DNIE_KSM.getCertificateChain(alias)[1];
		chain[2] = dnieRootCertificate;
		return chain;
	}

	/** {@inheritDoc} */
	@Override
	public KeyStore.PrivateKeyEntry getKeyEntry(final String alias,
                                                final PasswordCallback pssCallback) throws KeyStoreException,
                                                       									   NoSuchAlgorithmException,
                                                       									   UnrecoverableEntryException {
		if (!DNIE_ALIASES.contains(alias) || this.DNIE_KSM == null) {
			return this.ORIGINAL_KSM.getKeyEntry(alias, pssCallback);
		}
		return new PrivateKeyEntry(this.DNIE_KSM.getKeyEntry(alias, null).getPrivateKey(), this.getCertificateChain(alias));
	}

	/** {@inheritDoc} */
    @Override
	public List<KeyStore> getKeyStores() {
    	if (this.DNIE_KSM == null) {
    		return this.ORIGINAL_KSM.getKeyStores();
    	}
    	final List<KeyStore> ksms = new ArrayList<KeyStore>(this.ORIGINAL_KSM.getKeyStores().size() + 1);
    	ksms.addAll(this.ORIGINAL_KSM.getKeyStores());
    	ksms.addAll(this.DNIE_KSM.getKeyStores());
    	return ksms;
    }

    /** {@inheritDoc} */
    @Override
	public AOKeyStore getType() {
        return this.ORIGINAL_KSM.getType();
    }

    /** {@inheritDoc} */
    @Override
    public List<KeyStore> init(final AOKeyStore type,
            final InputStream store,
            final PasswordCallback pssCallBack,
            final Object[] params) throws AOKeyStoreManagerException,
                                          IOException {
    	throw new UnsupportedOperationException();
    }
}