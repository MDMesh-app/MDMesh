package com.mdmesh.policies.guice;

import com.hmdm.guice.module.AbstractLiquibaseModule;

import javax.servlet.ServletContext;

/** Runs the plugin's changelog: its {@code plugins} row (step 1 adds no table). */
public class PoliciesLiquibaseModule extends AbstractLiquibaseModule {

    public PoliciesLiquibaseModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getChangeLogResourcePath() {
        return "liquibase/policies.changelog.xml";
    }
}
