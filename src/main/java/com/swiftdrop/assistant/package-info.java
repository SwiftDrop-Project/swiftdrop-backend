/**
 * Assistant: the AI delivery assistant. A chat that answers delivery questions ("Where is my parcel?", "How much to
 * send 2 kg to Kandy?") by calling SwiftDrop's own APIs as tools, and the same tools exposed over MCP.
 *
 * <ul>
 *   <li>{@code POST /api/v1/assistant/chat} (public): tracking, price estimates, districts.</li>
 *   <li>{@code POST /api/v1/assistant/chat/me} (logged in): the same, plus the customer's or merchant's own
 *   parcels.</li>
 *   <li>{@code POST /api/v1/mcp} (public): the public tools as an MCP server (Streamable HTTP, JSON-RPC 2.0).</li>
 * </ul>
 *
 * <p>The model is Google Gemini (function calling) when {@code ASSISTANT_PROVIDER=gemini} and {@code GEMINI_API_KEY}
 * are set; otherwise, and whenever Gemini fails, a built-in rules provider answers with the same tools. Nothing is
 * stored: the browser sends the conversation with each message.
 *
 * <p>Owner: D.M.A.I. Disanayaka. Database schema: none. Events: none. Depends on tracking, pricing and shared.
 */
@ApplicationModule(displayName = "Assistant")
package com.swiftdrop.assistant;

import org.springframework.modulith.ApplicationModule;
