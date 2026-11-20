/**
 * Assistant: the AI delivery assistant. A chat that answers delivery questions ("Where is my parcel?", "How much to
 * send 2 kg to Kandy?") from the data of SwiftDrop's own APIs (tracking and pricing), and the same data tools for AI clients over MCP.
 *
 * <ul>
 *   <li>{@code POST /api/v1/assistant/chat} (public): tracking, price estimates, districts.</li>
 *   <li>{@code POST /api/v1/assistant/chat/me} (logged in): the same, plus the customer's or merchant's own
 *   parcels.</li>
 *   <li>{@code POST /api/v1/mcp} (public): the public data tools as an MCP server (Streamable HTTP, JSON-RPC 2.0);
 *   the client's own AI decides which tool to call, SwiftDrop only returns the data. Gemini is not used here.</li>
 * </ul>
 *
 * <p>SwiftDrop's own code (the rules provider) understands the question and gets the data; Google Gemini only
 * rewrites the answer clearly from that data, when {@code ASSISTANT_PROVIDER=gemini} and {@code GEMINI_API_KEY} are
 * set. Gemini has no tools, and an answer with an amount or tracking number that isn't in the data is replaced by
 * the plain one. Nothing is stored: the browser sends the conversation with each message.
 *
 * <p>Owner: D.M.A.I. Disanayaka. Database schema: none. Events: none. Depends on tracking, pricing and shared.
 */
@ApplicationModule(displayName = "Assistant")
package com.swiftdrop.assistant;

import org.springframework.modulith.ApplicationModule;
