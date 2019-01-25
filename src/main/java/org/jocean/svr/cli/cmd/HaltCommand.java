package org.jocean.svr.cli.cmd;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;

import org.jocean.cli.CliCommand;
import org.jocean.cli.CliContext;
import org.jocean.j2se.unit.UnitAgentMXBean;

public class HaltCommand implements CliCommand<CliContext> {

    @Override
    public String execute(final CliContext ctx, final String... args) throws Exception {
        final MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        final UnitAgentMXBean unitAgentMXBean = ManagementFactory.newPlatformMXBeanProxy(mbeanServer,
                "org.jocean:unit=root,name=unitAgent", UnitAgentMXBean.class);

        unitAgentMXBean.deleteAllUnit();
        return "all unit deleted.";
    }

    @Override
    public String getAction() {
        return "halt";
    }

    @Override
    public String getHelp() {
        return null;
    }
}

