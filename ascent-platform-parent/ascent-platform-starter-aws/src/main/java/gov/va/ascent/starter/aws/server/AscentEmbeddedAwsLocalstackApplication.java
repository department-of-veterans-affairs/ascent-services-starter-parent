package gov.va.ascent.starter.aws.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.CollectionUtils;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesResult;
import com.amazonaws.services.sqs.model.QueueAttributeName;

import cloud.localstack.DockerTestUtils;
import cloud.localstack.docker.DockerExe;
import cloud.localstack.docker.LocalstackDocker;
import gov.va.ascent.framework.config.AscentCommonSpringProfiles;
import gov.va.ascent.framework.exception.AscentRuntimeException;
import gov.va.ascent.framework.log.AscentLogger;
import gov.va.ascent.framework.log.AscentLoggerFactory;
import gov.va.ascent.starter.aws.s3.config.S3Properties;
import gov.va.ascent.starter.aws.server.AscentAwsLocalstackProperties.Services;
import gov.va.ascent.starter.aws.sqs.config.SqsProperties;

/**
 * This class will start and stop AWS localstack services, to be used for local envs. The profile embedded-aws needs to be added in
 * order for this bean to be created The class is renamed to end with Application so that it could be disabled for test coverage
 * violation.
 *
 * @author akulkarnis
 */
@Configuration
@Profile(AscentCommonSpringProfiles.PROFILE_EMBEDDED_AWS)
@EnableConfigurationProperties({ AscentAwsLocalstackProperties.class, SqsProperties.class, S3Properties.class })
public class AscentEmbeddedAwsLocalstackApplication {

	/** The Constant LOGGER. */
	private static final AscentLogger LOGGER = AscentLoggerFactory.getLogger(AscentEmbeddedAwsLocalstackApplication.class);

	private static final int MAX_RETRIES = 3000;

	private static LocalstackDocker localstackDocker = LocalstackDocker.getLocalstackDocker();

	private static String externalHostName = "localhost";
	private static boolean pullNewImage = true;
	private static boolean randomizePorts = false;
	private static Map<String, String> environmentVariables = new HashMap<>();

	/**
	 * Localstack Properties Bean
	 */
	@Autowired
	private AscentAwsLocalstackProperties ascentAwsLocalstackProperties;

	@Autowired
	private SqsProperties sqsProperties;
	
	@Autowired
	private S3Properties s3Properties;

	@Value("${aws.s3.bucket:sourcebucket}")
	private String sourcebucket;

	@Value("${aws.s3.target.bucket:targetbucket}")
	private String targetbucket;

	public LocalstackDocker getLocalstackDocker() {
		return localstackDocker;
	}

	/**
	 * Start embedded AWS servers on context load
	 *
	 * @throws IOException
	 */
	@PostConstruct
	public void startAwsLocalStack() {
		if (localstackDocker != null && localstackDocker.getLocalStackContainer() != null) {
			LOGGER.info("AWS localstack already running, not trying to re-start: {} ", localstackDocker.getLocalStackContainer());
			return;
		} else if (localstackDocker != null) {
			// clean the localstack
			cleanAwsLocalStack();
			localstackDocker.setExternalHostName(externalHostName);
			localstackDocker.setPullNewImage(pullNewImage);
			localstackDocker.setRandomizePorts(randomizePorts);

			List<Services> listServices = ascentAwsLocalstackProperties.getServices();

			if (!CollectionUtils.isEmpty(listServices)) {
				LOGGER.info("Services List: {}", ReflectionToStringBuilder.toString(listServices));
				StringBuilder builder = new StringBuilder();
				for (Services service : listServices) {
					builder.append(service.getName());
					builder.append(":");
					builder.append(service.getPort());
					builder.append(",");
				}
				// Remove last delimiter with setLength.
				builder.setLength(builder.length() - 1);
				String services = String.join(",", builder.toString());
				if (StringUtils.isNotEmpty(services)) {
					LOGGER.info("Services to be started: {}", services);
					environmentVariables.put("SERVICES", services);
				}
				localstackDocker.setEnvironmentVariables(environmentVariables);
				localstackDocker.setRandomizePorts(false);
			}
			// create and start S3, SQS API mock
			LOGGER.info("starting localstack: {} ", ReflectionToStringBuilder.toString(localstackDocker));
			localstackDocker.startup();

			createBuckets();
			createQueues();
		}
	}

	private void createBuckets() {
		AmazonS3 amazonS3Client = DockerTestUtils.getClientS3();

		// retry the operation until the localstack responds
		for (int i = 0; i < MAX_RETRIES; i++) {
			try {
				amazonS3Client.createBucket(sourcebucket);
				break;
			} catch (Exception e) {
				if (i == MAX_RETRIES - 1) {
					throw new AscentRuntimeException("AWS Local Stack (S3 createBucket " + sourcebucket
							+ ") failed to initialize after " + MAX_RETRIES + " tries.");
				}
				LOGGER.warn("Attempt to access AWS Local Stack client.createBucket(" + sourcebucket + ") failed on try # " + (i + 1)
						+ ", waiting for AWS localstack to finish initializing.");
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// NOSONAR do nothing
			}
		}
		// retry the operation until the localstack responds
		for (int i = 0; i < MAX_RETRIES; i++) {
			try {
				amazonS3Client.createBucket(targetbucket);
				break;
			} catch (Exception e) {
				if (i == MAX_RETRIES - 1) {
					throw new AscentRuntimeException("AWS Local Stack (S3 createBucket " + targetbucket
							+ ") failed to initialize after " + MAX_RETRIES + " tries.");
				}
				LOGGER.warn("Attempt to access AWS Local Stack client.createBucket(" + targetbucket + ") failed on try # " + (i + 1)
						+ ", waiting for AWS localstack to finish initializing.");
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// NOSONAR do nothing
			}
		}
	}

	private void createQueues() {
		AmazonSQS client = DockerTestUtils.getClientSQS();

		String deadletterQueueUrl = null;
		GetQueueAttributesResult queueAttributesResult = null;

		// retry the operation until the localstack responds
		for (int i = 0; i < MAX_RETRIES; i++) {
			try {
				deadletterQueueUrl = client.createQueue(sqsProperties.getDLQQueueName()).getQueueUrl();
				break;
			} catch (Exception e) {
				if (i == MAX_RETRIES - 1) {
					throw new AscentRuntimeException("AWS Local Stack (SQS create " + sqsProperties.getDLQQueueName()
							+ ") failed to initialize after " + MAX_RETRIES + " tries.");
				}
				LOGGER.warn("Attempt to access AWS Local Stack client.createQueue(" + sqsProperties.getDLQQueueName()
						+ ") failed on try # " + (i + 1)
						+ ", waiting for AWS localstack to finish initializing.");
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// NOSONAR do nothing
			}
		}

		GetQueueAttributesRequest getAttributesRequest =
				new GetQueueAttributesRequest(deadletterQueueUrl).withAttributeNames(QueueAttributeName.QueueArn);

		// retry the operation until the localstack responds
		for (int i = 0; i < MAX_RETRIES; i++) {
			try {
				queueAttributesResult = client.getQueueAttributes(getAttributesRequest);
				break;
			} catch (Exception e) {
				if (i == MAX_RETRIES - 1) {
					throw new AscentRuntimeException(
							"AWS Local Stack (SQS Get DLQ Attributes) failed to initialize after " + MAX_RETRIES + " tries.");
				}
				LOGGER.warn("Attempt to access AWS Local Stack client.getQueueAttributes(..) failed on try # " + (i + 1)
						+ ", waiting for AWS localstack to finish initializing.");
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// NOSONAR do nothing
			}
		}

		String redrivePolicy = "{\"maxReceiveCount\":\"1\", \"deadLetterTargetArn\":\""
				+ queueAttributesResult.getAttributes().get(QueueAttributeName.QueueArn.name()) + "\"}";

		Map<String, String> attributeMap = new HashMap<>();
		attributeMap.put("DelaySeconds", sqsProperties.getDelay().toString());
		attributeMap.put("MaximumMessageSize", sqsProperties.getMaxmessagesize());
		attributeMap.put("MessageRetentionPeriod", sqsProperties.getMessageretentionperiod());
		attributeMap.put("ReceiveMessageWaitTimeSeconds", sqsProperties.getWaittime().toString());
		attributeMap.put("VisibilityTimeout", sqsProperties.getVisibilitytimeout().toString());
		attributeMap.put("FifoQueue", sqsProperties.getQueuetype().toString());  
		attributeMap.put("ContentBasedDeduplication", sqsProperties.getContentbasedduplication().toString());
		attributeMap.put(QueueAttributeName.RedrivePolicy.name(), redrivePolicy);

		// retry the operation until the localstack responds
		for (int i = 0; i < MAX_RETRIES; i++) {
			try {
				client.createQueue(new CreateQueueRequest(sqsProperties.getQueueName()).withAttributes(attributeMap));
				break;
			} catch (Exception e) {
				if (i == MAX_RETRIES - 1) {
					throw new AscentRuntimeException("AWS Local Stack (SQS create " + sqsProperties.getQueueName()
							+ ") failed to initialize after " + MAX_RETRIES + " tries.");
				}
				LOGGER.warn("Attempt to access AWS Local Stack client.createQueue(" + sqsProperties.getQueueName()
						+ ") failed on try # " + (i + 1)
						+ ", waiting for AWS localstack to finish initializing.");
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// NOSONAR do nothing
			}
		}
	}

	/**
	 * stop embedded AWS servers on context destroy
	 */
	@PreDestroy
	public void stopAwsLocalStack() {
		// stop the localstack
		if (localstackDocker != null && localstackDocker.getLocalStackContainer() != null) {
			LOGGER.info("stopping localstack: {} ", localstackDocker.getLocalStackContainer());
			localstackDocker.stop();
			LOGGER.info("stopped localstack");
		}
		// clean the localstack
		cleanAwsLocalStack();
	}

	/**
	 * clean AWS Localstack containers
	 */
	private void cleanAwsLocalStack() {
		// clean up docker containers
		DockerExe newDockerExe = new DockerExe();
		String listContainerIds =
				newDockerExe.execute(Arrays.asList("ps", "--no-trunc", "-aq", "--filter", "ancestor=localstack/localstack"));
		LOGGER.info("containers to be cleaned: {} ", listContainerIds);
		if (StringUtils.isNotEmpty(listContainerIds)) {
			try {
				String[] splitArray = listContainerIds.split("\\s+");
				for (String containerId : splitArray) {
					String output = newDockerExe.execute(Arrays.asList("rm", "-f", containerId));
					LOGGER.info("docker remove command output: {} ", output);
				}
			} catch (PatternSyntaxException ex) {
				LOGGER.warn("PatternSyntaxException During Splitting: {}", ex);
			}
		}
	}
}
