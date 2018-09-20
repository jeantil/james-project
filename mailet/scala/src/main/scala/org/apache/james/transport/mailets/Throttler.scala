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

  private def parseCount(config: MailetConfig) = {
    Try(config.getInitParameter(Throttler.COUNT_PARAM).toInt)
      .getOrElse(
        throw new MailetException(
          s"Missing or invalid configuration parameter ${Throttler.COUNT_PARAM} " +
            s"for mailet ${config.getMailetName} ($getMailetInfo)"))
  }

  private def parsePeriod(config: MailetConfig) = {
    val duration = Try(Duration(config.getInitParameter(Throttler.PERIOD_PARAM)))
      .getOrElse(
        throw new MailetException(
          s"Missing or invalid configuration parameter ${Throttler.PERIOD_PARAM} " +
            s"for mailet ${config.getMailetName} ($getMailetInfo)"))
    if (duration.isFinite()) {
      FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS)
    } else {
      throw new MailetException(s"${Throttler.PERIOD_PARAM} must be a finite duration")
    }
  }

  override def service(mail: Mail): Unit = {
    val sender = mail.getSender
    val now = Instant.now(clock)
    val limit = now.minusMillis(period.toMillis)
    val updatedRates = safeRates.updateAndGet(rates => {
      val instants: Seq[Instant] = rates(sender)
      rates + (sender -> (now +: instants.filter(_.isAfter(limit))))
    })

    if (updatedRates(sender).size > count) {
      mail.setState(Mail.ERROR)
      mail.setErrorMessage("454 Throttling – Maximum sending rate exceeded")
    }

  }

  override def destroy(): Unit = ()

  override def getMailetConfig: MailetConfig = config

  override def getMailetInfo: String = "Email Throttler"
}
