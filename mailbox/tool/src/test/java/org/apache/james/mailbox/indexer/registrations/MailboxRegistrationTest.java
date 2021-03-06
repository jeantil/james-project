/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.indexer.registrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Lists;
import org.apache.james.mailbox.MailboxListener;
import org.apache.james.mailbox.indexer.events.FlagsMessageEvent;
import org.apache.james.mailbox.indexer.events.MessageDeletedEvent;
import org.apache.james.mailbox.mock.MockMailboxSession;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.store.SimpleMessageMetaData;
import org.apache.james.mailbox.store.TestId;
import org.apache.james.mailbox.store.event.EventFactory;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailbox;
import org.junit.Before;
import org.junit.Test;

import javax.mail.Flags;
import java.util.Date;
import java.util.TreeMap;

public class MailboxRegistrationTest {

    public static final MailboxPath INBOX = new MailboxPath("#private", "btellier@apache.org", "INBOX");
    public static final Long UID = 18L;
    public static final int UID_VALIDITY = 45;
    public static final SimpleMailbox<TestId> MAILBOX = new SimpleMailbox<TestId>(INBOX, UID_VALIDITY);
    public static final MockMailboxSession SESSION = new MockMailboxSession("test");
    public static final int MOD_SEQ = 21;
    public static final int SIZE = 41;
    public static final Flags NEW_FLAGS = new Flags(Flags.Flag.ANSWERED);
    private MailboxRegistration mailboxRegistration;
    private EventFactory<TestId> eventFactory;

    @Before
    public void setUp() {
        eventFactory = new EventFactory<TestId>();
        mailboxRegistration = new MailboxRegistration(INBOX);
    }

    @Test
    public void reportedEventsShouldBeInitiallyEmpty() {
        assertThat(mailboxRegistration.getImpactingEvents(UID)).isEmpty();
    }


    @Test
    public void AddedEventsShouldNotBeReported() {
        TreeMap<Long, MessageMetaData> treeMap = new TreeMap<Long, MessageMetaData>();
        treeMap.put(UID, new SimpleMessageMetaData(UID, MOD_SEQ, new Flags(), SIZE, new Date()));
        MailboxListener.Event event = eventFactory.added(SESSION, treeMap, MAILBOX);
        mailboxRegistration.event(event);
        assertThat(mailboxRegistration.getImpactingEvents(UID)).isEmpty();
    }

    @Test
    public void ExpungedEventsShouldBeReported() {
        TreeMap<Long, MessageMetaData> treeMap = new TreeMap<Long, MessageMetaData>();
        treeMap.put(UID, new SimpleMessageMetaData(UID, MOD_SEQ, new Flags(), SIZE, new Date()));
        MailboxListener.Event event = eventFactory.expunged(SESSION, treeMap, MAILBOX);
        mailboxRegistration.event(event);
        assertThat(mailboxRegistration.getImpactingEvents(UID)).containsExactly(new MessageDeletedEvent(INBOX, UID));
    }

    @Test
    public void FlagsEventsShouldBeReported() {
        MailboxListener.Event event = eventFactory.flagsUpdated(SESSION,
            Lists.newArrayList(UID),
            MAILBOX,
            Lists.newArrayList(new UpdatedFlags(UID, MOD_SEQ, new Flags(), NEW_FLAGS)));
        mailboxRegistration.event(event);
        assertThat(mailboxRegistration.getImpactingEvents(UID)).containsExactly(new FlagsMessageEvent(INBOX, UID, NEW_FLAGS));
    }

}
