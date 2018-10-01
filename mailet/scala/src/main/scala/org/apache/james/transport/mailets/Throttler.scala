/** **************************************************************
  * Licensed to the Apache Software Foundation (ASF) under one   *
  * or more contributor license agreements.  See the NOTICE file *
  * distributed with this work for additional information        *
  * regarding copyright ownership.  The ASF licenses this file   *
  * to you under the Apache License, Version 2.0 (the            *
  * "License"); you may not use this file except in compliance   *
  * with the License.  You may obtain a copy of the License at   *
  * *
  * http://www.apache.org/licenses/LICENSE-2.0                 *
  * *
  * Unless required by applicable law or agreed to in writing,   *
  * software distributed under the License is distributed on an  *
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
  * KIND, either express or implied.  See the License for the    *
  * specific language governing permissions and limitations      *
  * under the License.                                           *
  * ***************************************************************/

package org.apache.james.transport.mailets

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

import org.apache.james.core.MailAddress
import org.apache.mailet.{Mail, Mailet, MailetConfig, MailetException}

object Throttler {
  val PERIOD_PARAM = "period"
  val COUNT_PARAM = "count"
}

/**
  * <p>This mailet keeps track of the sending rate for each sender
  * going through the email server (instance based). It rejects emails with
  * <i>"454 Throttling – Maximum sending rate exceeded"</i> when the rate goes
  * over a fixed limit.</p>
  * <p/>
  * <p>The tracking is done using a list of timestamps of messages previously
  * seen for each individual sender. This means that allowing 3600 messages per
  * hour may end up consuming more memory (upto 3600 java.time.Instant instances
  * per sender if they continuously send emails) than 600 messages per 10
  * minutes</p>
  * <p/>
  * <p>This mailet expects 2 configuration parameters :
  * <ul>
  * <li><i>count</i> is expected to be an integer
  * <li><i>period</i> is expected to be an finite duration greater than 1ms.
  * It can be expressed using the scala duration DSL.
  * </ul>
  *</p>
  * <pre><code>
  * &lt;mailet match=&quot;All&quot; class=&quot;&lt;Throttler&gt;&quot;&gt;
  * &lt;period&gt;1 minute&lt;/period&gt;
  * &lt;count&gt;10&lt;/count&gt;
  * &lt;/mailet&gt;
  * </code></pre>
  *
  * <p>The scala Duration dsl expects a number (integer or fractional) followed by
  * a unit. The unit can be one of
  *<ul>
  * <li> d|day|days, h|hour|hours
  * <li> min|minute|minutes
  * <li> s|second|seconds
  * <li> ms|milli|millis|millisecond|milliseconds
  * <li> µs|micro|micros|microsecond|microseconds
  * <li> ns|nano|nanos|nanosecond|nanoseconds
  *</ul>
  * for exemple the following strings are valid durations:</p>
  *<pre><code>
  * 10 seconds
  * 1 day
  * 8h
  * 100ns
  * 80millis
  *</code></pre>
  *
  */
class Throttler(clock: Clock = Clock.systemUTC()) extends Mailet {
  private var config: MailetConfig = _
  private val safeRates: AtomicReference[Map[MailAddress, Seq[Instant]]] =
    new AtomicReference(Map.empty.withDefaultValue(Seq.empty))

  private var period: FiniteDuration = _
  private var count: Int = _

  override def init(config: MailetConfig): Unit = {
    this.period = parsePeriod(config)
    this.count = parseCount(config)
    this.config = config
  }

  private def parseCount(config: MailetConfig): Int = Try(config.getInitParameter(Throttler.COUNT_PARAM).toInt)
    .getOrElse(
      throw new MailetException(
        s"Missing or invalid configuration parameter ${Throttler.COUNT_PARAM} " +
          s"for mailet ${config.getMailetName} ($getMailetInfo)"))

  private def parsePeriod(config: MailetConfig): FiniteDuration = {
    val duration = Try(Duration(config.getInitParameter(Throttler.PERIOD_PARAM)))
      .getOrElse(
        throw new MailetException(
          s"Missing or invalid configuration parameter ${Throttler.PERIOD_PARAM} " +
            s"for mailet ${config.getMailetName} ($getMailetInfo)"))
    if (duration.isFinite()) {
      if (duration.toMillis > 0) {
        FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS)
      } else {
        throw new MailetException(
          s"${Throttler.PERIOD_PARAM} must be a finite duration greater or equal to 1ms")
      }
    } else {
      throw new MailetException(
        s"${Throttler.PERIOD_PARAM} must be a finite duration greater or equal to 1ms")
    }
  }

  override def service(mail: Mail): Unit = {
    val sender = mail.getSender
    val now = Instant.now(clock)
    val limit = now.minusMillis(period.toMillis)
    val updatedRates = safeRates.updateAndGet(rates => {
      val instants = rates(sender)
      rates + (sender -> (now +: instants.filter(_.isAfter(limit))))
    })

    if (updatedRates(sender).size > count) {
      mail.setState(Mail.ERROR)
      mail.setErrorMessage("454 Throttling – Maximum sending rate exceeded")
    }

  }

  override def destroy(): Unit = ()

  override def getMailetConfig: MailetConfig = config

  override def getMailetInfo = "Email Throttler"
}
