package gov.va.ascent.starter.aws.server;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import gov.va.ascent.framework.log.AscentLogger;
import gov.va.ascent.framework.log.AscentLoggerFactory;

@Configuration
public class AscentAwsLocalstackProperties {

	static final AscentLogger LOGGER = AscentLoggerFactory.getLogger(AscentAwsLocalstackProperties.class);
	
	@SuppressWarnings("serial")
	private List<Services> services = new ArrayList<Services>() {{
        add(new Services("s3",4572));
        add(new Services("sqs",4576));
    }};

	public void setServices(List<Services> services) {
		this.services = services;
    }

	public List<Services> getServices() {
		return this.services;
	}

	/** Inner class with Services specific config properties */
	public static class Services {

		/** AWS Service name */
		private String name;

		/** AWS service port */
		private int port;
		
		public Services(String name, int port) {
			super();
			this.name = name;
			this.port = port;
		}
		
		public Services() {
			super();
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}
	}
}
