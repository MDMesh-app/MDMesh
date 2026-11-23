/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import liquibase.change.CheckSum;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The changelog as Liquibase itself reads it at startup (schema-validated, element order included), and the checksums
 * a database that ran an earlier build of a changeSet may have recorded.
 */
public class LiquibaseChangelogParseTest {

    private static DatabaseChangeLog parse() throws Exception {
        ResourceAccessor accessor = new ClassLoaderResourceAccessor(LiquibaseChangelogParseTest.class.getClassLoader());
        return ChangeLogParserFactory.getInstance().getParser("liquibase/db.changelog.xml", accessor)
                .parse("liquibase/db.changelog.xml", new ChangeLogParameters(), accessor);
    }

    @Test
    public void liquibaseParsesTheChangelog() throws Exception {
        assertTrue(parse().getChangeSets().size() > 100);
    }

    /**
     * Builds 2f60d172 to ba627ad1 recorded the fit/focal changeSet with its SQL before IF NOT EXISTS; their databases
     * must still start (Liquibase validates recorded checksums), so that checksum stays valid.
     */
    @Test
    public void theFitFocalChangeSetAcceptsTheEarlierBuildsChecksum() throws Exception {
        ChangeSet fitFocal = null;
        for (ChangeSet cs : parse().getChangeSets()) {
            if ("26.11.11-configurations-backgroundImageFitFocal".equals(cs.getId())) fitFocal = cs;
        }
        assertNotNull(fitFocal);
        assertTrue(fitFocal.isCheckSumValid(CheckSum.parse("9:cb0e74fa797947549547ac1d31d0a37f")));
        // And the current one, of course.
        assertTrue(fitFocal.isCheckSumValid(fitFocal.generateCheckSum(liquibase.ChecksumVersion.latest())));
    }
}
