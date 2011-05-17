/**
 * Copyright (C) 2009-2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.jmsbenchmark

import org.apache.activemq.spring.ActiveMQConnectionFactory
import java.lang.Thread
import javax.jms._
import org.apache.activemq.command.{ActiveMQTopic, ActiveMQQueue}
import sun.tools.tree.WhileStatement

/**
 * <p>
 * Simulates load on a JMS sever.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class JMSClientScenario extends Scenario {

  def createProducer(i:Int) = {
    new ProducerClient(i)
  }
  def createConsumer(i:Int) = {
    new ConsumerClient(i)
  }

  protected def destination(i:Int) = destination_type match {
    case "queue" => new ActiveMQQueue(queue_prefix+destination_name+"-"+(i%destination_count))
    case "topic" => new ActiveMQTopic(topic_prefix+destination_name+"-"+(i%destination_count))
    case _ => throw new Exception("Unsuported destination type: "+destination_type)
  }

  def factory = {
    val rc = new ActiveMQConnectionFactory
    rc.setBrokerURL(url)
    rc.setUserName(user_name)
    rc.setPassword(password)
    rc
  }

  def jms_ack_mode = {
    ack_mode match {
      case "auto" => Session.AUTO_ACKNOWLEDGE
      case "client" => Session.CLIENT_ACKNOWLEDGE
      case "dups_ok" => Session.DUPS_OK_ACKNOWLEDGE
      case "transacted" => Session.SESSION_TRANSACTED
      case _ => throw new Exception("Invalid ack mode: "+ack_mode)
    }
  }

  trait JMSClient extends Client {

    @volatile
    var connection:Connection = _
    var message_counter=0L

    var worker = new Thread() {
      override def run() {
        var reconnect_delay = 0
        while( !done.get ) {
          try {

            if( reconnect_delay!=0 ) {
              Thread.sleep(reconnect_delay)
              reconnect_delay=0
            }
            connection = factory.createConnection()
            connection.setClientID(name)
            connection.setExceptionListener(new ExceptionListener {
              def onException(exception: JMSException) {
              }
            })
            connection.start()

            execute

          } catch {
            case e:Throwable =>
              if( display_errors ) {
                e.printStackTrace
              }
              error_counter.incrementAndGet
              reconnect_delay = 1000
          } finally {
            dispose
          }
        }
      }
    }

    def dispose {
      try {
        connection.close()
      } catch {
        case _ =>
      }
    }

    def execute:Unit

    def start = {
      worker.start
    }

    def shutdown = {
      assert(done.get)
      if ( worker!=null ) {
        while(worker.isAlive ) {
          worker.interrupt()
          worker.join(100)
        }
        worker = null
      }
    }

    def name:String
  }

  class ConsumerClient(val id: Int) extends JMSClient {
    val name: String = "consumer " + id

    def execute {
      var session = connection.createSession(false, jms_ack_mode)
      var consumer:MessageConsumer = if( durable ) {
        session.createDurableSubscriber(destination(id).asInstanceOf[Topic], name, selector, no_local)
      } else {
        session.createConsumer(destination(id), selector, no_local)
      }

      while( !done.get() ) {
        val msg = consumer.receive(500)
        if( msg!=null ) {
          consumer_counter.incrementAndGet()
          if (consumer_sleep != 0) {
            Thread.sleep(consumer_sleep)
          }
          if(session.getAcknowledgeMode == Session.CLIENT_ACKNOWLEDGE) {
            msg.acknowledge();
          }
        }
      }
    }

  }

  class ProducerClient(val id: Int) extends JMSClient {

    val name: String = "producer " + id

    def execute {
      val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
      val producer:MessageProducer = session.createProducer(destination(id))
      producer.setDeliveryMode(if( persistent ) {
        DeliveryMode.PERSISTENT
      } else {
        DeliveryMode.NON_PERSISTENT
      })

      val msg = session.createTextMessage(body(name))
      headers_for(id).foreach { case (key, value) =>
        msg.setStringProperty(key, value)
      }

      while( !done.get() ) {
        producer.send(msg)
        producer_counter.incrementAndGet()
        if (producer_sleep != 0) {
          Thread.sleep(producer_sleep)
        }
      }

    }
  }

  def body(name:String) = {
    val buffer = new StringBuffer(message_size)
    buffer.append("Message from " + name+"\n")
    for( i <- buffer.length to message_size ) {
      buffer.append(('a'+(i%26)).toChar)
    }
    var rc = buffer.toString
    if( rc.length > message_size ) {
      rc.substring(0, message_size)
    } else {
      rc
    }
  }



}
