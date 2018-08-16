package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.OfflineCause;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.model.common.Link;
import org.openstack4j.model.compute.Addresses;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jenkins Slave node.
 */
public class JCloudsSlave extends AbstractCloudSlave implements TrackedItem {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(JCloudsSlave.class.getName());

    private final @Nonnull String cloudName;
    // Full/effective options
    private /*final*/ @Nonnull SlaveOptions options;
    private final @Nonnull ProvisioningActivity.Id provisioningId;
    private transient @Nonnull Cache<Object, Object> cache;

    private /*final*/ @Nonnull String nodeId;

    private final long created = System.currentTimeMillis();

    // Backward compatibility
    private transient @Deprecated int overrideRetentionTime;
    private transient @Deprecated String jvmOptions;
    private transient @Deprecated String credentialsId;
    private transient @Deprecated String slaveType; // converted to string for easier conversion
    private transient @Deprecated Server metadata;

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
        this.nodeId = metadata.getId();
        this.cache = makeCache();
        setLauncher(new JCloudsLauncher(getLauncherFactory().createLauncher(this)));
    }

    // In 2.0, "nodeId" was removed and replaced by "metadata". Then metadata was deprecated in favour of "nodeId" again.
    // The configurations stored are expected to have at least one of them.
    @SuppressWarnings({"unused", "deprecation"})
    @SuppressFBWarnings({"RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", "The fields are non-null after readResolve"})
    protected Object readResolve() {
        super.readResolve();
        cache = makeCache();
        if (options == null) {
            // Node options are not of override of anything so we need to ensure this fill all mandatory fields
            // We base the outdated config on current plugin defaults to increase the chance it will work.
            SlaveOptions.Builder builder = JCloudsCloud.DescriptorImpl.getDefaultOptions().getBuilder()
                    .jvmOptions(Util.fixEmpty(jvmOptions))
            ;

            LauncherFactory lf = "SSH".equals(slaveType)
                    ? new LauncherFactory.SSH(credentialsId)
                    : LauncherFactory.JNLP.JNLP
            ;
            builder.launcherFactory(lf);

            if (overrideRetentionTime > 0) {
                builder = builder.retentionTime(overrideRetentionTime);
            }

            options = builder.build();
            jvmOptions = null;
            credentialsId = null;
            slaveType = null;
        }

        if (metadata != null && (nodeId == null || !nodeId.equals(metadata.getId()))) {
            nodeId = metadata.getId();
            metadata = null;
        }

        nodeId =  nodeId.replaceFirst(".*/", ""); // Remove region prefix

        return this;
    }

    /**
     * Get settings from OpenStack about the Server for this slave.
     * 
     * @return A Map of fieldName to value. This will not be null or empty.
     */
    public @Nonnull Map<String, String> getLiveOpenstackServerDetails() {
        final Callable<Map<String, String>> loader = new Callable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return readLiveOpenstackServerDetails();
            }
        };
        return getCachableData("liveData", loader);
    }

    /**
     * Gets most of the Server settings that were provided to Openstack
     * when the slave was created by the plugin.
     * Not all settings are interesting and any that are empty/null are omitted.
     * 
     * @return A Map of option name to value. This will not be null or empty.
     */
    public @Nonnull Map<String, String> getOpenstackSlaveData() {
        final Callable<Map<String, String>> loader = new Callable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return readOpenstackSlaveData();
            }
        };
        return getCachableData("staticData", loader);
    }

    private @Nonnull Map<String, String> readLiveOpenstackServerDetails() {
        final Map<String, String> result = new LinkedHashMap<>();
        final Server s = readOpenstackServer();
        if (s == null) {
            return result;
        }
        final Addresses addresses = s.getAddresses();
        if (addresses != null) {
            putIfNotNullOrEmpty(result, "Addresses", addresses.getAddresses());
        }
        putIfNotNullOrEmpty(result, "AvailabilityZone", s.getAvailabilityZone());
        putIfNotNullOrEmpty(result, "ConfigDrive", s.getConfigDrive());
        putIfNotNullOrEmpty(result, "Created", s.getCreated());
        putIfNotNullOrEmpty(result, "Fault", s.getFault());
        final Flavor flavor = s.getFlavor();
        if (flavor != null) {
            putIfNotNullOrEmpty(result, "Flavor.Name", flavor.getName());
            putIfNotNullOrEmpty(result, "Flavor.Vcpus", flavor.getVcpus());
            putIfNotNullOrEmpty(result, "Flavor.Ram", flavor.getRam());
            putIfNotNullOrEmpty(result, "Flavor.Disk", flavor.getDisk());
            if (flavor.getEphemeral() != 0) {
                putIfNotNullOrEmpty(result, "Flavor.Ephemeral", flavor.getEphemeral());
            }
            if (flavor.getSwap() != 0) {
                putIfNotNullOrEmpty(result, "Flavor.Swap", flavor.getSwap());
            }
        }
        putIfNotNullOrEmpty(result, "Host", s.getHost());
        putIfNotNullOrEmpty(result, "HypervisorHostname", s.getHypervisorHostname());
        putIfNotNullOrEmpty(result, "Image", s.getImage());
        putIfNotNullOrEmpty(result, "InstanceName", s.getInstanceName());
        putIfNotNullOrEmpty(result, "KeyName", s.getKeyName());
        putIfNotNullOrEmpty(result, "LaunchedAt", s.getLaunchedAt());
        final List<? extends Link> links = s.getLinks();
        if (links != null && !links.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (final Link link : links) {
                sb.append('\n');
                sb.append(link.getHref());
            }
            sb.deleteCharAt(0);
            putIfNotNullOrEmpty(result, "Links", sb);
        }
        putIfNotNullOrEmpty(result, "Name", s.getName());
        putIfNotNullOrEmpty(result, "OsExtendedVolumesAttached", s.getOsExtendedVolumesAttached());
        putIfNotNullOrEmpty(result, "PowerState", s.getPowerState());
        putIfNotNullOrEmpty(result, "Status", s.getStatus());
        putIfNotNullOrEmpty(result, "TerminatedAt", s.getTerminatedAt());
        putIfNotNullOrEmpty(result, "Updated", s.getUpdated());
        final Map<String, String> metaDataOrNull = s.getMetadata();
        if (metaDataOrNull != null) {
            for (Map.Entry<String, String> e : metaDataOrNull.entrySet()) {
                putIfNotNullOrEmpty(result, "Metadata." + e.getKey(), e.getValue());
            }
        }
        return result;
    }

    private @Nonnull Map<String, String> readOpenstackSlaveData() {
        final Map<String, String> result = new LinkedHashMap<>();
        final SlaveOptions slaveOptions = getSlaveOptions();
        putIfNotNullOrEmpty(result, "ServerId", nodeId);
        putIfNotNullOrEmpty(result, "NetworkId", slaveOptions.getNetworkId());
        putIfNotNullOrEmpty(result, "FloatingIpPool", slaveOptions.getFloatingIpPool());
        putIfNotNullOrEmpty(result, "SecurityGroups", slaveOptions.getSecurityGroups());
        putIfNotNullOrEmpty(result, "StartTimeout", slaveOptions.getStartTimeout());
        putIfNotNullOrEmpty(result, "KeyPairName", slaveOptions.getKeyPairName());
        final Object launcherFactory = slaveOptions.getLauncherFactory();
        putIfNotNullOrEmpty(result, "LauncherFactory",
                launcherFactory == null ? null : launcherFactory.getClass().getSimpleName());
        putIfNotNullOrEmpty(result, "JvmOptions", slaveOptions.getJvmOptions());
        return result;
    }

    private Server readOpenstackServer() {
        try {
            final Server server = getOpenstack(cloudName).getServerById(nodeId);
            return server;
        } catch (NoSuchElementException ex) {
            // just return empty
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING,
                    "Unable to read details of server '" + nodeId + "' from cloud '" + cloudName + "'.", ex);
        }
        return null;
    }

    /**
     * Get public IP address of the server.
     *
     * @throws NoSuchElementException The server does not exist anymore. Plugin should not get slave to this state ever
     * but there is no way to prevent external machine deletion.
     */
    public @CheckForNull String getPublicAddress() throws NoSuchElementException {

        return Openstack.getPublicAddress(getOpenstack(cloudName).getServerById(nodeId));
    }
    /**
     * Get public IP address of the server.
     */
    @Restricted(NoExternalUse.class)
    public @CheckForNull String getPublicAddressIpv4() throws NoSuchElementException {

        return Openstack.getPublicAddressIpv4(getOpenstack(cloudName).getServerById(nodeId));
    }

    /**
     * Get effective options used to configure this slave.
     */
    public @Nonnull SlaveOptions getSlaveOptions() {
        options.getClass();
        return options;
    }

    public @Nonnull LauncherFactory getLauncherFactory() {
        LauncherFactory lf = options.getLauncherFactory();
        return lf == null ? LauncherFactory.JNLP.JNLP : lf;
    }

    // Exposed for testing
    /*package*/ @Nonnull String getServerId() {
        return nodeId;
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

    public long getCreatedTime() {
        return created;
    }

    @Override public JCloudsComputer getComputer() {
        return (JCloudsComputer) super.getComputer();
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
    protected void _terminate(TaskListener listener) {
        CloudStatistics cloudStatistics = CloudStatistics.get();
        ProvisioningActivity activity = cloudStatistics.getActivityFor(this);
        if (activity != null) {
            activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
            // Attach what is likely a reason for the termination
            OfflineCause offlineCause = getFatalOfflineCause();
            if (offlineCause != null) {
                PhaseExecutionAttachment attachment = new PhaseExecutionAttachment(ProvisioningActivity.Status.WARN, offlineCause.toString());
                cloudStatistics.attach(activity, ProvisioningActivity.Phase.COMPLETED, attachment);
            }
        }

        // Wrap deletion disposables into statistics tracking disposables
        AsyncResourceDisposer.get().dispose(
                new RecordDisposal(
                        new DestroyMachine(cloudName, nodeId),
                        provisioningId
                )
        );
    }

    private @CheckForNull OfflineCause getFatalOfflineCause() {
        JCloudsComputer computer = getComputer();
        if (computer == null) return null;
        return computer.getFatalOfflineCause();
    }

    /** Gets something from the cache, loading it into the cache if necessary. */
    @SuppressWarnings("unchecked")
    private @Nonnull <K, V> V getCachableData(@Nonnull final K key, @Nonnull final Callable<V> dataloader) {
        try {
            final Object result = cache.get(key, dataloader);
            return (V) result;
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(e);
        }
    }

    /** Creates a cache where data will be kept for a short duration before being discarded. */
    private static @Nonnull <K, V> Cache<K, V> makeCache() {
        return CacheBuilder.newBuilder().expireAfterWrite(20, TimeUnit.SECONDS).build();
    }

    private static void putIfNotNullOrEmpty(@Nonnull final Map<String, String> mapToBeAddedTo, @Nonnull final String fieldName,
            @CheckForNull final Object fieldValue) {
        if (fieldValue != null) {
            final String valueString = fieldValue.toString();
            if (!valueString.trim().isEmpty()) {
                mapToBeAddedTo.put(fieldName, valueString);
            }
        }
    }

    private static Openstack getOpenstack(String cloudName) {
        return JCloudsCloud.getByName(cloudName).getOpenstack();
    }

    private final static class RecordDisposal implements Disposable {
        private static final long serialVersionUID = -3623764445481732365L;

        private final @Nonnull Disposable inner;
        private final @Nonnull ProvisioningActivity.Id provisioningId;

        private RecordDisposal(@Nonnull Disposable inner, @Nonnull ProvisioningActivity.Id provisioningId) {
            this.inner = inner;
            this.provisioningId = provisioningId;
        }

        @Override
        public @Nonnull State dispose() throws Throwable {
            try {
                return inner.dispose();
            } catch (Throwable ex) {
                CloudStatistics statistics = CloudStatistics.get();
                ProvisioningActivity activity = statistics.getPotentiallyCompletedActivityFor(provisioningId);
                if (activity != null) {
                    PhaseExecutionAttachment.ExceptionAttachment attachment = new PhaseExecutionAttachment.ExceptionAttachment(
                            ProvisioningActivity.Status.WARN, ex
                    );
                    statistics.attach(activity, ProvisioningActivity.Phase.COMPLETED, attachment);
                }
                throw ex;
            }
        }

        @Override
        public @Nonnull String getDisplayName() {
            return inner.getDisplayName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RecordDisposal that = (RecordDisposal) o;

            if (!inner.equals(that.inner)) return false;
            return provisioningId.equals(that.provisioningId);
        }

        @Override
        public int hashCode() {
            int result = inner.hashCode();
            result = 31 * result + provisioningId.hashCode();
            return result;
        }
    }
}
