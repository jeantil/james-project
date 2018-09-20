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

import java.time.{Instant, ZoneOffset}

import org.apache.mailet.base.test.{FakeMail, FakeMailetConfig}
import org.apache.mailet.{Mail, MailetException}
import org.specs2.mutable.Specification
import org.threeten.extra.MutableClock

class ThrottlerSpec extends Specification {
  val defaultConfig = FakeMailetConfig.builder()
    .mailetName("throttler")
    .setProperty("period", "1s")
    .setProperty("count", "1")
    .build()

  val JANE = "jane@example.com"
  val JOHN = "john@example.com"

  "Throttler " should {
    "not start if the count parameter is absent from the config" in {
      val defaultConfig = FakeMailetConfig.builder()
        .mailetName("throttler")
        .setProperty("period", "1s")
        .build()
      val throttler = new Throttler()
      throttler.init(defaultConfig) should throwA[MailetException]

    }
    "not start if the period parameter is absent from the config" in {
      val defaultConfig = FakeMailetConfig.builder()
        .mailetName("throttler")
        .setProperty("count", "1")
        .build()
      val throttler = new Throttler()
      throttler.init(defaultConfig) should throwA[MailetException]
    }
    "not start if the count parameter is not a number" in {
      val defaultConfig = FakeMailetConfig.builder()
        .mailetName("throttler")
        .setProperty("period", "1s")
        .setProperty("count", "a")
        .build()
      val throttler = new Throttler()
      throttler.init(defaultConfig) should throwA[MailetException]
    }
    "not start if the period parameter is not a duration" in {
      val defaultConfig = FakeMailetConfig.builder()
        .mailetName("throttler")
        .setProperty("period", "foo")
        .setProperty("count", "a")
        .build()
      val throttler = new Throttler()
      throttler.init(defaultConfig) should throwA[MailetException]
    }
    "allow mail to pass if rate limit is not exceeded" in {
      val clock = MutableClock.of(Instant.now(), ZoneOffset.UTC)
      val throttler = new Throttler(clock)
      throttler.init(defaultConfig)

      val mail = FakeMail.builder()
        .sender(JANE)
        .recipient(JOHN)
        .state(Mail.DEFAULT)
        .build()

      throttler.service(mail)

      mail.getState must be_===(Mail.DEFAULT)
    }
    "allow mail to pass if rate limit is exceeded" in {
      val clock = MutableClock.of(Instant.now(), ZoneOffset.UTC)
      val throttler = new Throttler(clock)
      throttler.init(defaultConfig)

      val acceptedMail = FakeMail.builder()
        .sender(JANE)
        .recipient(JOHN)
        .state(Mail.DEFAULT)
        .build()
      val rejectedMail = FakeMail.builder()
        .sender(JANE)
        .recipient(JOHN)
        .state(Mail.DEFAULT)
        .build()

      throttler.service(acceptedMail)
      throttler.service(rejectedMail)

      acceptedMail.getState must be_===(Mail.DEFAULT)
      rejectedMail.getState must be_===(Mail.ERROR)
      rejectedMail.getErrorMessage must contain("454 Throttling")
    }
  }
}
