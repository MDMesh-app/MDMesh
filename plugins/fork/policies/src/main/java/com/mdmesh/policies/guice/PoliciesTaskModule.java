package com.mdmesh.policies.guice;

import com.hmdm.plugin.PluginTaskModule;
import com.mdmesh.policies.service.JobWorker;

import javax.inject.Inject;

/** Starts the job worker when the server starts. */
public class PoliciesTaskModule implements PluginTaskModule {
    private final JobWorker worker;

    @Inject
    public PoliciesTaskModule(JobWorker worker) {
        this.worker = worker;
    }

    @Override
    public void init() {
        worker.start();
    }
}
