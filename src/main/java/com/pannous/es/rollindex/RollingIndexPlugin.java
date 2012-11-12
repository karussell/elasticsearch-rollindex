package com.pannous.es.rollindex;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.rest.RestModule;

/**
 * @author Peter Karich
 */
public class RollingIndexPlugin extends AbstractPlugin {

    protected final ESLogger logger = Loggers.getLogger(RollingIndexPlugin.class);

    @Override public String name() {
        return "rollindex";
    }

    @Override public String description() {
        return "Rolling Index Plugin";
    }

    @Override public void processModule(Module module) {
        if (module instanceof RestModule)
            ((RestModule) module).addRestAction(RollAction.class);
    }
}
