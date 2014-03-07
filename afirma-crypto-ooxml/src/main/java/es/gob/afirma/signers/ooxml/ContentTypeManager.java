package es.gob.afirma.signers.ooxml;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import es.gob.afirma.core.AOException;

/** Clase para la lectura de los content types declarados en un documento OOXML y la
 * identificaci&oacute;n del content type de un fichero en base a ellos. */
final class ContentTypeManager {

	private final Map<String, String> defaultContentTypes = new HashMap<String, String>();
	private final Map<String, String> overrideContentTypes = new HashMap<String, String>();

	private static final DocumentBuilderFactory DOC_FACTORY = DocumentBuilderFactory.newInstance();
	static {
		DOC_FACTORY.setNamespaceAware(true);
	}

	private static final String SLASH = "/"; //$NON-NLS-1$

	/** Crea un ContentTypeManager que nos permitira conocer el contentType asociado a cada elemento
	 * del documento.
	 * @param contentTypeIs Flujo de datos de entrada del fichero [Content_Types].xml
	 * @throws SAXException Cuando el XML esta mal formado.
	 * @throws IOException Cuando ocurre un error al leer el XML.
	 * @throws ParserConfigurationException Cuando no se puede crear el constructor de XML. */
	ContentTypeManager(final InputStream contentTypeIs) throws SAXException, IOException, ParserConfigurationException {

		final Document contentTypeDocument = loadDocument(contentTypeIs);
		final NodeList nodeList = contentTypeDocument.getChildNodes();

		if (nodeList.getLength() > 0) {
			// Nodo Types
			Node typeNode = nodeList.item(0);
			final NodeList typeList = typeNode.getChildNodes();

			// Nodos contenidos en Types
			for (int i = 0; i < typeList.getLength(); i++) {
				try {
					typeNode = typeList.item(i);
					if (typeNode.getNodeType() != Node.ELEMENT_NODE) {
		    			continue;
		    		}
					if ("Default".equals(typeNode.getNodeName())) { //$NON-NLS-1$
						final NamedNodeMap attNodes = typeNode.getAttributes();
						this.defaultContentTypes.put(getAttributeValue(attNodes, "Extension"), getAttributeValue(attNodes, "ContentType")); //$NON-NLS-1$ //$NON-NLS-2$
					}
					else if ("Override".equals(typeNode.getNodeName())) { //$NON-NLS-1$
						final NamedNodeMap attNodes = typeNode.getAttributes();
						this.overrideContentTypes.put(getAttributeValue(attNodes, "PartName"), getAttributeValue(attNodes, "ContentType")); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
				catch (final AOException e) {
					Logger.getLogger("es.gob.afirma").warning("Se encontro un nodo en el [Content_Types].xml no valido: " + e);  //$NON-NLS-1$//$NON-NLS-2$
					continue;
				}
			}
		}
	}

	/** Convierte el inputstream de un XML en un DOM Document. */
	private static Document loadDocument(final InputStream documentInputStream) throws ParserConfigurationException, SAXException, IOException {
		return getNewDocumentBuilder().parse(documentInputStream);
	}

	/** Devuelve una nueva instancia del DocumentBuilder. */
	private static DocumentBuilder getNewDocumentBuilder() throws ParserConfigurationException {
		return DOC_FACTORY.newDocumentBuilder();
	}

	/** Recupera el valor de un atributo.
	 * @return Valor del atributo.
	 * @throws AOException Cuando el nodo o el atributo no existen. */
	private static String getAttributeValue(final NamedNodeMap nodeMap, final String attrName) throws AOException {
		if (nodeMap == null) {
			throw new AOException("El nodo no contenia atributos"); //$NON-NLS-1$
		}
		final Node attNode = nodeMap.getNamedItem(attrName);
		if (attNode == null) {
			throw new AOException("No existe el atributo: " + attrName); //$NON-NLS-1$
		}
		return attNode.getNodeValue();
	}

	/** Recupera el ContentType correspondiente a un fichero interno del OOXML.
	 * @param partName Ruta de fichero.
	 * @return ContentType definido para ese fichero. */
	String getContentType(final String partName) {
		String partNameFix;
		if (!partName.startsWith(SLASH)) {
			partNameFix = SLASH.concat(partName);
		}
		else {
			partNameFix = partName;
		}

		if (this.overrideContentTypes.containsKey(partNameFix)) {
			return this.overrideContentTypes.get(partNameFix);
		}

		final String ext = getExtension(partNameFix);
		if (ext != null && this.defaultContentTypes.containsKey(ext)) {
			return this.defaultContentTypes.get(ext);
		}

		return null;
	}

	/** Devuelve la extendion de un nombre de fichero.
	 * @param partName Ruta del fichero.
	 * @return Extension o {@code null} si no la hay. */
	private static String getExtension(final String partName) {
		final int dotPos = partName.lastIndexOf('.');
		if (dotPos == -1 || dotPos == partName.length() - 1) {
			return null;
		}
		return partName.substring(dotPos + 1);
	}
}
