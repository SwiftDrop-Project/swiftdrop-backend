package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.tools.Caller;
import java.util.List;

/** Answers the user's latest message, calling the assistant's tools as needed. */
public interface ChatProvider {

    /**
     * @param conversation oldest first, ending with the user's new message
     * @throws ProviderUnavailableException when the model can't be reached or gives no answer
     */
    ChatReply reply(List<Turn> conversation, Caller caller);
}
