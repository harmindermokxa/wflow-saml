package org.joget.plugin.saml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.TimeZone;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.codec.binary.Base64;
import org.joget.commons.util.LogUtil;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class SamlResponse {

    private Document xmlDoc;
    private NodeList assertions;
    private Element rootElement;
    private final AccountSettings accountSettings;
    private final Certificate certificate;
    private String currentUrl;

    public SamlResponse(AccountSettings accountSettings) throws CertificateException {
        this.accountSettings = accountSettings;
        certificate = new Certificate();
        certificate.loadCertificate(this.accountSettings.getCertificate());
    }
    public SamlResponse()
    {
    	this.accountSettings = new AccountSettings();
		this.certificate = new Certificate();
		
    }

    public void loadXml(String xml) throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
        DocumentBuilderFactory fty = DocumentBuilderFactory.newInstance();
        fty.setNamespaceAware(true);
        // XMLConstants with FEATURE_SECURE_PROCESSING prevents external document access. (XXE/XEE Possible Attacks).
        fty.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder builder = fty.newDocumentBuilder();
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes());
        xmlDoc = builder.parse(bais);
        // Loop through the doc and tag every element with an ID attribute as an XML ID node.
        XPath xpath = XPathFactory.newInstance().newXPath();
        XPathExpression expr = xpath.compile("//*[@ID]");
        NodeList nodeList = (NodeList) expr.evaluate(xmlDoc, XPathConstants.NODESET);
        for (int i = 0; i < nodeList.getLength(); i++) {
            Element elem = (Element) nodeList.item(i);
            Attr attr = (Attr) elem.getAttributes().getNamedItem("ID");
            elem.setIdAttributeNode(attr, true);
        }
    }

    public void loadXmlFromBase64(String response) throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
        Base64 base64 = new Base64();
        byte[] decodedB = base64.decode(response);
        String decodedS = new String(decodedB);
       
        loadXml(decodedS);
    }

    // isValid() function should be called to make basic security checks to responses.
    public boolean isValid() throws Exception {
        // Security Checks
        rootElement = xmlDoc.getDocumentElement();
        assertions = xmlDoc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Assertion");
        xmlDoc.getDocumentElement().normalize();

        // Check SAML version
        String attName = rootElement.getAttribute("Version");
        if (!attName.equals("2.0")) {
            throw new Exception("Unsupported SAML Version.");
        }

        // Check ID in the response
        attName = rootElement.getAttribute("ID");
        if (attName.equals("")) {
            throw new Exception("Missing ID attribute on SAML Response.");
        }

        if (assertions == null || assertions.getLength() != 1) {
            throw new Exception("SAML Response must contain 1 Assertion.");
        }

        NodeList nodes = xmlDoc.getElementsByTagNameNS("*", "Signature");
        if (nodes == null || nodes.getLength() == 0) {
            throw new Exception("Can't find signature in Document.");
        }

        // Check destination
        String destinationUrl = rootElement.getAttribute("Destination");
        if (destinationUrl != null) {
            if (!destinationUrl.equals(currentUrl)) {
                throw new Exception("The response was received at " + currentUrl + " instead of " + destinationUrl);
            }
        }

        // Check Audience 
        NodeList nodeAudience = xmlDoc.getElementsByTagNameNS("*", "Audience");
        String audienceUrl = nodeAudience.item(0).getChildNodes().item(0).getNodeValue();
        if (audienceUrl != null) {
            if (!audienceUrl.equals(currentUrl)) {
                throw new Exception(audienceUrl + " is not a valid audience for this Response");
            }
        }

        // Check SubjectConfirmation, at least one SubjectConfirmation must be valid
        NodeList nodeSubConf = xmlDoc.getElementsByTagNameNS("*", "SubjectConfirmation");
        boolean validSubjectConfirmation = true;
        for (int i = 0; i < nodeSubConf.getLength(); i++) {
            Node method = nodeSubConf.item(i).getAttributes().getNamedItem("Method");
            if (method != null && !method.getNodeValue().equals("urn:oasis:names:tc:SAML:2.0:cm:bearer")) {
                continue;
            }
            NodeList childs = nodeSubConf.item(i).getChildNodes();
            for (int c = 0; c < childs.getLength(); c++) {
                if (childs.item(c).getLocalName().equals("SubjectConfirmationData")) {
                    Node inResponseTo = childs.item(c).getAttributes().getNamedItem("InResponseTo");
//					if(inResponseTo != null && !inResponseTo.getNodeValue().equals("ID of the AuthNRequest")){
//						validSubjectConfirmation = false;
//					}
                    Node recipient = childs.item(c).getAttributes().getNamedItem("Recipient");
                    if (recipient != null && !recipient.getNodeValue().equals(currentUrl)) {
                        validSubjectConfirmation = false;
                    }
                    Node notOnOrAfter = childs.item(c).getAttributes().getNamedItem("NotOnOrAfter");
                    if (notOnOrAfter != null) {
                        final Calendar notOnOrAfterDate = javax.xml.bind.DatatypeConverter.parseDateTime(notOnOrAfter.getNodeValue());
                        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                        if (notOnOrAfterDate.before(now)) {
                            validSubjectConfirmation = false;
                        }
                    }
                    Node notBefore = childs.item(c).getAttributes().getNamedItem("NotBefore");
                    if (notBefore != null) {
                        final Calendar notBeforeDate = javax.xml.bind.DatatypeConverter.parseDateTime(notBefore.getNodeValue());
                        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                        if (notBeforeDate.before(now)) {
                            validSubjectConfirmation = false;
                        }
                    }
                }
            }
        }
        if (!validSubjectConfirmation) {
            throw new Exception("A valid SubjectConfirmation was not found on this Response");
        }

//		if (setIdAttributeExists()) {
//			tagIdAttributes(xmlDoc);
//		}
        X509Certificate cert = certificate.getX509Cert();
        DOMValidateContext ctx = new DOMValidateContext(cert.getPublicKey(), nodes.item(0));
        XMLSignatureFactory sigF = XMLSignatureFactory.getInstance("DOM");
        XMLSignature xmlSignature = sigF.unmarshalXMLSignature(ctx);

        return xmlSignature.validate(ctx);
    }

    public String getNameId() throws Exception {
        NodeList nodes = xmlDoc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
        if (nodes.getLength() == 0) {
            throw new Exception("No name id found in Document.");
        }
        return nodes.item(0).getTextContent();
    }

    public String getAttribute(String name) {
        HashMap attributes = getAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            Object val = attributes.get(name);
            return (val != null) ? val.toString() : null;
        }
        return null;
    }

    public HashMap getAttributes() {
        HashMap<String, ArrayList> attributes = new HashMap<String, ArrayList>();
        NodeList nodes = xmlDoc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Attribute");

        if (nodes.getLength() != 0) {
            for (int i = 0; i < nodes.getLength(); i++) {
                NamedNodeMap attrName = nodes.item(i).getAttributes();
                String attName = attrName.getNamedItem("Name").getNodeValue();
                NodeList children = nodes.item(i).getChildNodes();

                ArrayList<String> attrValues = new ArrayList<String>();
                for (int j = 0; j < children.getLength(); j++) {
                    attrValues.add(children.item(j).getTextContent());
                }
                attributes.put(attName, attrValues);
            }
        } else {
            return null;
        }
        return attributes;
    }

    private boolean setIdAttributeExists() {
        for (Method method : Element.class.getDeclaredMethods()) {
            if (method.getName().equals("setIdAttribute")) {
                return true;
            }
        }
        return false;
    }

    private void tagIdAttributes(Document xmlDoc) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void setDestinationUrl(String urld) {
        currentUrl = urld;
    }
    
    
}
