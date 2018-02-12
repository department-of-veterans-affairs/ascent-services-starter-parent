package gov.va.ascent.starter.aws.autoconfigure.sqs.services.impl;

import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.ProducerCallback;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.stereotype.Service;

import com.amazon.sqs.javamessaging.SQSConnection;

import gov.va.ascent.starter.aws.autoconfigure.sqs.config.SqsProperties;
import gov.va.ascent.starter.aws.autoconfigure.sqs.services.SQSServices;


@Service
public class SQSServicesImpl implements SQSServices {
	
	private Logger logger = LoggerFactory.getLogger(SQSServicesImpl.class);
	private SQSConnection connection;
	
	@Resource
	JmsOperations jmsOperations;
	
	@Autowired
	ConnectionFactory connectionFactory;

	private SqsProperties sqsProperties;

	@Autowired
	public void setApp(SqsProperties sqsProperties) {
		this.sqsProperties = sqsProperties;
	}

	@PostConstruct
	public void init() {
		startJmsConnection();
	}

	@PreDestroy
	public void cleanUp() throws Exception {
		connection.close();
		logger.info("Spring Container is destroy! Customer clean up");
	}
	
	@Override
	@ManagedOperation
	public ResponseEntity<String> sendMessage(String request) {
			logger.info("Handling request: '{}'", request);
			
		    final String messageId = jmsOperations.execute(new ProducerCallback<String>() {
				@Override
				public String doInJms(Session session, MessageProducer producer) throws JMSException {
					 final TextMessage message = session.createTextMessage(request);
			            producer.send(message);
			            logger.debug("Sent JMS message with payload='{}', id: '{}'", request, message.getJMSMessageID());
			            return message.getJMSMessageID();
				}
		    });
		    
		    return new ResponseEntity<>(messageId, HttpStatus.OK);
	}
    
	public void startJmsConnection() {
		try {
			connection = (SQSConnection) connectionFactory.createConnection();

			// Create the session
			Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
			MessageConsumer consumer = session.createConsumer(session.createQueue(sqsProperties.getQueueName()));
			ReceiverCallback callback = new ReceiverCallback();
			consumer.setMessageListener(callback);

			// No messages are processed until this is called
			connection.start();
			logger.info("Connection started");

			try {
				callback.waitForOneMinuteOfSilence();
			} catch (InterruptedException e) {
				logger.error(e.getMessage());
				e.printStackTrace();
			}
			logger.info("Returning after one minute of silence");

		} catch (JMSException e) {
			logger.error(e.getMessage());
			e.printStackTrace();
		}
	}

	private static class ReceiverCallback implements MessageListener {
		private Logger logger = LoggerFactory.getLogger(ReceiverCallback.class);
		// Used to listen for message silence
		private volatile long timeOfLastMessage = System.nanoTime();

		public void waitForOneMinuteOfSilence() throws InterruptedException {
			for (;;) {
				long timeSinceLastMessage = System.nanoTime() - timeOfLastMessage;
				long remainingTillOneMinuteOfSilence = TimeUnit.MINUTES.toNanos(1) - timeSinceLastMessage;
				if (remainingTillOneMinuteOfSilence < 0) {
					break;
				}
				TimeUnit.NANOSECONDS.sleep(remainingTillOneMinuteOfSilence);
			}
		}

		@Override
		public void onMessage(Message arg0) {
			try {
				// ExampleCommon.handleMessage(arg0);
				arg0.acknowledge();
				logger.info("Acknowledged message " + arg0.getJMSMessageID());
				timeOfLastMessage = System.nanoTime();

			} catch (JMSException e) {
				logger.error("Error processing message: " + e.getMessage());
				e.printStackTrace();
			}
		}
	}
}
