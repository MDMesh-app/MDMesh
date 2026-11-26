package com.mdmesh.policies.guice;

import com.hmdm.guice.module.AbstractPersistenceModule;

import javax.servlet.ServletContext;

/** The plugin's MyBatis mappers (read-only queries over core's tables). */
public class PoliciesPersistenceModule extends AbstractPersistenceModule {

    public PoliciesPersistenceModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getMapperPackageName() {
        return "com.mdmesh.policies.persistence.mapper";
    }

    @Override
    protected String getDomainObjectsPackageName() {
        return "com.mdmesh.policies.persistence.domain";
    }
}
