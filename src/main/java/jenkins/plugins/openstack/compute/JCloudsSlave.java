package jenkins.plugins.openstack.compute;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.openstack4j.model.compute.Server;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * Jenkins Slave node.
 */
public class JCloudsSlave extends AbstractCloudSlave implements TrackedItem {
    private static final Logger LOGGER = Logger.getLogger(JCloudsSlave.class.getName());

    private @Nonnull Server metadata;

    private final @Nonnull String cloudName;
    private /*final*/ @Nonnull SlaveOptions options;
    private final ProvisioningActivity.Id provisioningId;

    // Backward compatibility
    private transient @Deprecated int overrideRetentionTime;
    private transient @Deprecated String jvmOptions;
    private transient @Deprecated String credentialsId;
    private transient @Deprecated JCloudsCloud.SlaveType slaveType;

    public JCloudsSlave(
            @Nonnull ProvisioningActivity.Id id, @Nonnull Server metadata, @Nonnull String labelString, @Nonnull SlaveOptions slaveOptions
    ) throws IOException, Descriptor.FormException {
        super(
                metadata.getName(),
                null,
                slaveOptions.getFsRoot(),
                slaveOptions.getNumExecutors(),
                Mode.NORMAL,
                labelString,
                null,
                new JCloudsRetentionStrategy(),
                Collections.singletonList(new EnvironmentVariablesNodeProperty(
                        new EnvironmentVariablesNodeProperty.Entry("OPENSTACK_PUBLIC_IP", Openstack.getPublicAddress(metadata))
                ))
        );
        this.cloudName = id.getCloudName(); // TODO deprecate field
        this.provisioningId = id;
        this.options = slaveOptions;
        this.metadata = metadata;
        setLauncher(new JCloudsLauncher(getSlaveType().createLauncher(this)));
    }

    @SuppressWarnings({"unused", "deprecation"})
    protected Object readResolve() {
        super.readResolve(); // Call parent
        if (options == null) {
            options = SlaveOptions.builder()
                    .retentionTime(overrideRetentionTime)
                    .jvmOptions(jvmOptions)
                    .credentialsId(credentialsId)
                    .slaveType(slaveType)
                    .build()
            ;
            jvmOptions = null;
            credentialsId = null;
            slaveType = null;
        }

        return this;
    }

    /**
     * Get public IP address of the server.
     */
    public @CheckForNull String getPublicAddress() {
        return Openstack.getPublicAddress(metadata);
    }

    /**
     * Get effective options used to configure this slave.
     */
    public @Nonnull SlaveOptions getSlaveOptions() {
        return options;
    }

    public JCloudsCloud.SlaveType getSlaveType() {
        return options.getSlaveType();
    }

    @Override
    public AbstractCloudComputer<JCloudsSlave> createComputer() {
        LOGGER.info("Creating a new computer for " + getNodeName());
        return new JCloudsComputer(this);
    }

    @Override
    public @Nonnull ProvisioningActivity.Id getId() {
        return this.provisioningId;
    }

    @Extension
    public static final class JCloudsSlaveDescriptor extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "JClouds Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        Openstack os = JCloudsCloud.getByName(cloudName).getOpenstack();
        os.destroyServer(metadata);
    }
}
