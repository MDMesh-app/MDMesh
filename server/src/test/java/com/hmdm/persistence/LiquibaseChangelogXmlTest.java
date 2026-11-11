/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** The migration file is parsed at server startup; reject malformed changeSet nesting in unit tests. */
public class LiquibaseChangelogXmlTest {

    @Test
    public void changelogIsWellFormedXmlWithAChangeSetPerTopLevelChild() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try (InputStream in = LiquibaseChangelogXmlTest.class.getResourceAsStream("/liquibase/db.changelog.xml")) {
            assertNotNull("db.changelog.xml on the classpath", in);
            Document document = factory.newDocumentBuilder().parse(in);
            assertEquals("databaseChangeLog", document.getDocumentElement().getNodeName());
            assertEquals(topLevelChangeSets(document), document.getElementsByTagName("changeSet").getLength());
        }
    }

    private static int topLevelChangeSets(Document document) {
        int count = 0;
        org.w3c.dom.NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if ("changeSet".equals(children.item(i).getNodeName())) count++;
        }
        return count;
    }
}
