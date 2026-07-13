package com.imsw.observe.pipeline.infrastructure.source;

import com.imsw.observe.kernel.error.MessageParseException;
import com.imsw.observe.kernel.event.model.Event;

public interface MessageParser<M> {

    Event parse(M raw) throws MessageParseException;
}
