/**
 * OpenSpec task 7.3 — integration coverage for two areas whose real seam is
 * `App.tsx`'s `SignedInShell`, not a smaller unit:
 *
 * - Area 3 (pending → sent/failed reconciliation): `SignedInShell`'s
 *   `handleMessageAck`/`handleRealtimeError` are the only callers of
 *   `usePendingMessages`' `removePending`/`markPendingFailed` in the app, so
 *   the end-to-end MESSAGE_ACK/ERROR → pending-bubble transition is exercised
 *   here (`usePendingMessages.test.tsx` covers the hook's own state machine
 *   in isolation).
 * - Area 5 (missed-history refresh): the reconnect → `invalidateQueries` →
 *   `fetchMergedHistory` wiring lives in `SignedInShell`'s reconnect effect
 *   (NOT in `useChatSocket.ts`, which only mirrors connection state) — this
 *   is that real seam, verified for both "no refetch on the initial connect"
 *   and "a newer WebSocket message survives the merge".
 *
 * The realtime transport is faked at its lowest edge, `createChatSocket`
 * (already covered unit-by-unit in `chatSocket.test.ts`), so `useChatSocket`
 * and all of `SignedInShell`'s real production wiring run untouched.
 */
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import * as api from "./api";
import type { Conversation, Message, User } from "./api";
import { EMPTY_PREVIEW } from "./conversationPreview";
import { COMPACT_QUERY } from "./hooks/useMediaQuery";
import { queryClient } from "./queryClient";
import * as realtime from "./realtime";
import type { ChatSocket, ChatSocketOptions } from "./realtime";

const USER_A: User = { id: "user-a", displayName: "Alice" };
const USER_B: User = { id: "user-b", displayName: "Bob" };
const CONVERSATION: Conversation = {
  id: "conv-1",
  participants: [USER_A, USER_B],
};

function msg(
  id: string,
  createdAt: string,
  overrides: Partial<Message> = {},
): Message {
  return {
    id,
    conversationId: CONVERSATION.id,
    senderId: USER_B.id,
    content: `content-${id}`,
    createdAt,
    ...overrides,
  };
}

interface CapturedSocket {
  options: ChatSocketOptions;
  sendMessage: ReturnType<typeof vi.fn>;
}

let captured: CapturedSocket[] = [];

function installFakeChatSocket(): void {
  vi.spyOn(realtime, "createChatSocket").mockImplementation(
    (options: ChatSocketOptions): ChatSocket => {
      const sendMessage = vi.fn();
      captured.push({ options, sendMessage });
      return {
        sendMessage,
        pendingCount: () => 0,
        getState: () => "connecting",
        terminate: vi.fn(),
      };
    },
  );
}

async function renderSignedInOnConversation(): Promise<
  ReturnType<typeof userEvent.setup>
> {
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  );
  await user.click(await screen.findByRole("button", { name: "Alice" }));
  // The second "Bob" is the rail row (the identity screen is gone). Its
  // accessible name carries the presence word since task 5.5 ("Bob Offline"
  // until a PRESENCE frame says otherwise), so it is matched by prefix — the
  // sign-in flow must not depend on the partner's current presence state.
  await user.click(await screen.findByRole("button", { name: /^Bob\b/ }));
  return user;
}

/** The conversation rail (slice 3 previews live here). */
function rail(): HTMLElement {
  return screen.getByRole("navigation");
}

/**
 * The active conversation's thread pane. Since slice 3, the rail ALSO renders
 * the latest message's content (its preview), so thread-only assertions
 * (`getAllByText(...)` counts, duplicate-render checks) must be scoped here —
 * an unscoped query would legitimately match the rail row too.
 */
function threadPane(): HTMLElement {
  const pane = document.querySelector<HTMLElement>(".thread-pane");
  expect(pane).not.toBeNull();
  return pane as HTMLElement;
}

beforeEach(() => {
  queryClient.clear();
  window.sessionStorage.clear();
  captured = [];
  installFakeChatSocket();
  vi.spyOn(api, "fetchUsers").mockResolvedValue([USER_A, USER_B]);
  vi.spyOn(api, "fetchConversations").mockResolvedValue([CONVERSATION]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("pending → sent/failed reconciliation (App-level)", () => {
  it("MESSAGE_ACK removes the pending bubble and renders the authoritative message once", async () => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    const user = await renderSignedInOnConversation();

    const textbox = await screen.findByRole("textbox", {
      name: /message to bob/i,
    });
    await user.type(textbox, "Hello there");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await within(threadPane()).findByText("Sending…")).toBeInTheDocument();
    expect(within(threadPane()).getByText("Hello there")).toBeInTheDocument();

    const socket = captured[0];
    expect(socket).toBeDefined();
    const sent = socket!.sendMessage.mock.calls[0]?.[0] as
      { clientMessageId: string } | undefined;
    expect(sent).toBeDefined();

    act(() => {
      socket!.options.onEvent?.({
        type: "MESSAGE_ACK",
        clientMessageId: sent!.clientMessageId,
        message: msg("server-1", "2026-08-30T12:00:00.000Z", {
          senderId: USER_A.id,
          content: "Hello there",
        }),
      });
    });

    await waitFor(() =>
      expect(within(threadPane()).queryByText("Sending…")).not.toBeInTheDocument(),
    );
    // Exactly one bubble with this content — no duplicate render. Scoped to
    // the thread pane: the rail ALSO displays "Hello there" since slice 3,
    // where the acked message became the conversation's preview (task 4.4).
    expect(within(threadPane()).getAllByText("Hello there")).toHaveLength(1);
    expect(within(rail()).getByText("Hello there")).toBeInTheDocument();
  });

  it("a correlated ERROR marks the pending bubble failed and never removes it", async () => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    const user = await renderSignedInOnConversation();

    const textbox = await screen.findByRole("textbox", {
      name: /message to bob/i,
    });
    await user.type(textbox, "This will fail");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await within(threadPane()).findByText("Sending…")).toBeInTheDocument();

    const socket = captured[0]!;
    const sent = socket.sendMessage.mock.calls[0]?.[0] as {
      clientMessageId: string;
    };

    act(() => {
      socket.options.onEvent?.({
        type: "ERROR",
        clientMessageId: sent.clientMessageId,
        code: "PERSISTENCE_ERROR",
        reason: "boom",
      });
    });

    expect(
      await within(threadPane()).findByText("Failed to send"),
    ).toBeInTheDocument();
    // The rail reads the same failure (task 4.3 override) — the unscoped
    // getByText would now find two, so the assertions stay thread-scoped.
    expect(within(rail()).getByText("Failed to send")).toBeInTheDocument();
    // The bubble is retained, not removed.
    expect(within(threadPane()).getByText("This will fail")).toBeInTheDocument();
  });
});

describe("composer submission availability (task 2.4, spec: empty submissions are unavailable)", () => {
  it("a whitespace-only draft cannot be submitted by button or Enter", async () => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    const user = await renderSignedInOnConversation();

    const textbox = await screen.findByRole("textbox", {
      name: /message to bob/i,
    });
    await user.type(textbox, "   ");

    // The affordance itself signals unavailability …
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    // … and Enter (the second submit path) no-ops as well.
    await user.keyboard("{Enter}");

    expect(captured[0]?.sendMessage).not.toHaveBeenCalled();
    expect(screen.queryByText("Sending…")).not.toBeInTheDocument();
  });
});

describe("compact-viewport rail overlay (task 2.6, spec: compact viewports use exclusive overlays)", () => {
  const originalMatchMedia = window.matchMedia;

  // Only the compact threshold matches; every other query (theme, reduced
  // motion) reports false, exactly like the global jsdom polyfill.
  beforeEach(() => {
    window.matchMedia = ((query: string): MediaQueryList =>
      ({
        matches: query === COMPACT_QUERY,
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }) as MediaQueryList) as typeof window.matchMedia;
  });

  afterEach(() => {
    window.matchMedia = originalMatchMedia;
  });

  async function openRailOverlay(
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<void> {
    await user.click(
      await screen.findByRole("button", { name: "Open conversations" }),
    );
    // While open, the toggle relabels to "Close conversations" (shared with
    // the scrim, which is rendered only while an overlay is open) — so
    // "Open conversations" disappearing IS the open-state observable.
    expect(
      screen.queryByRole("button", { name: "Open conversations" }),
    ).not.toBeInTheDocument();
    expect(document.querySelector("button.scrim")).not.toBeNull();
  }

  async function expectOverlayClosed(): Promise<void> {
    const toggle = await screen.findByRole("button", {
      name: "Open conversations",
    });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(document.querySelector("button.scrim")).toBeNull();
    // Every dismissal path (Escape, scrim, selection) routes through the same
    // focus-returning close — the rail is inert as it closes, so focus must
    // never be left on <body>.
    expect(document.activeElement).toBe(toggle);
  }

  it("the dismiss key (Escape) closes the open rail overlay", async () => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    const user = await renderSignedInOnConversation();
    await openRailOverlay(user);

    await user.keyboard("{Escape}");
    await expectOverlayClosed();
  });

  it("activating the scrim closes the open rail overlay", async () => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    const user = await renderSignedInOnConversation();
    await openRailOverlay(user);

    // The scrim and the open toggle share the "Close conversations" label, so
    // the scrim is located by its dedicated class (it is the only element
    // rendered with it, and only while an overlay is open).
    const scrim = document.querySelector("button.scrim");
    expect(scrim).not.toBeNull();
    await user.click(scrim as HTMLElement);
    await expectOverlayClosed();
  });

  it("selecting a conversation closes the open rail overlay", async () => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    const user = await renderSignedInOnConversation();
    await openRailOverlay(user);

    // The rail row for the Bob conversation (the identity screen's "Bob" is
    // gone — we are already signed in as Alice). The row's accessible name
    // carries the presence word (task 5.5); no PRESENCE has arrived yet, so
    // the honest default reads "Offline".
    await user.click(screen.getByRole("button", { name: "Bob Offline" }));
    await expectOverlayClosed();
  });
});

describe("missed-history refresh after reconnect", () => {
  it("does NOT refetch on the FIRST connected transition (initial load already owns it)", async () => {
    const fetchMessagesSpy = vi
      .spyOn(api, "fetchMessages")
      .mockResolvedValue([msg("m1", "2026-08-30T12:00:00.000Z")]);
    await renderSignedInOnConversation();
    await within(threadPane()).findByText("content-m1");
    expect(fetchMessagesSpy).toHaveBeenCalledTimes(1);

    const socket = captured[0]!;
    act(() => {
      socket.options.onStateChange?.("connected");
    });

    // Still just the one, initial-mount fetch.
    expect(fetchMessagesSpy).toHaveBeenCalledTimes(1);
  });

  it("refetches on reconnect and the merge cannot erase or duplicate a newer WebSocket message", async () => {
    const fetchMessagesSpy = vi.spyOn(api, "fetchMessages");
    fetchMessagesSpy.mockResolvedValueOnce([
      msg("m1", "2026-08-30T12:00:00.000Z"),
    ]);

    let resolveReconnectFetch: ((messages: Message[]) => void) | undefined;
    fetchMessagesSpy.mockImplementationOnce(
      () =>
        new Promise<Message[]>((resolve) => {
          resolveReconnectFetch = resolve;
        }),
    );

    await renderSignedInOnConversation();
    await within(threadPane()).findByText("content-m1");

    const socket = captured[0]!;
    // Establish the shell's FIRST connected transition (excluded from refetch).
    act(() => {
      socket.options.onStateChange?.("connected");
    });
    expect(fetchMessagesSpy).toHaveBeenCalledTimes(1);

    // Unexpected disconnect, then reconnect: this is what triggers the
    // invalidate → refetch (task 6.6's recovery mechanism).
    act(() => {
      socket.options.onStateChange?.("reconnecting");
    });
    act(() => {
      socket.options.onStateChange?.("connected");
    });

    await waitFor(() => expect(fetchMessagesSpy).toHaveBeenCalledTimes(2));
    expect(resolveReconnectFetch).toBeDefined();

    // While the refetch is still in flight, a NEW_MESSAGE lands via the
    // socket — this must survive the eventual merge commit even though the
    // in-flight server response snapshot predates it and will not include it.
    const midFlightMessage = msg("m2", "2026-08-30T12:00:05.000Z");
    act(() => {
      socket.options.onEvent?.({
        type: "NEW_MESSAGE",
        message: midFlightMessage,
      });
    });
    expect(await within(threadPane()).findByText("content-m2")).toBeInTheDocument();

    // The server's reconnect-triggered response reports history missed while
    // disconnected (m3) but — realistically for an in-flight snapshot — not
    // yet m2, which arrived after the server read.
    const missedMessage = msg("m3", "2026-08-30T12:00:10.000Z");
    await act(async () => {
      resolveReconnectFetch?.([
        msg("m1", "2026-08-30T12:00:00.000Z"),
        missedMessage,
      ]);
      await Promise.resolve();
    });

    // All three render, each exactly once, in chronological order — the
    // merge neither erased the mid-flight m2 nor duplicated m1/m3. Scoped to
    // the thread pane: the rail ALSO renders the tail ("content-m3") as its
    // preview since slice 3's task-4.4 patch.
    await waitFor(() =>
      expect(within(threadPane()).getByText("content-m3")).toBeInTheDocument(),
    );
    expect(within(threadPane()).getAllByText(/^content-m/)).toHaveLength(3);
    const order = within(threadPane())
      .getAllByText(/^content-m/)
      .map((el) => el.textContent);
    expect(order).toEqual(["content-m1", "content-m2", "content-m3"]);
    expect(within(rail()).getByText("content-m3")).toBeInTheDocument();
  });
});

/**
 * Slice 2 — conversation creation (OpenSpec tasks 3.5–3.9). The dialog, its
 * focus confinement and focus return, the in-flight guard, the success and
 * failure outcomes, and the CONVERSATION_CREATED rail reconciliation all live
 * at (or pass through) `SignedInShell`, so this file is their integration
 * seam, faking the REST calls through `./api` and the transport through
 * `createChatSocket` exactly as the suites above do.
 */
const USER_C: User = { id: "user-c", displayName: "Carol" };
const USER_D: User = { id: "user-d", displayName: "Dan" };
const DIRECTORY: User[] = [USER_A, USER_B, USER_C, USER_D];
const CONVERSATION_WITH_C: Conversation = {
  id: "conv-2",
  participants: [USER_A, USER_C],
};

function dialog(): HTMLElement {
  return screen.getByRole("dialog", { name: "New conversation" });
}

function candidateButton(name: string): HTMLButtonElement {
  return within(dialog()).getByRole("button", {
    name: `Start a conversation with ${name}`,
  });
}

async function openDialog(
  user: ReturnType<typeof userEvent.setup>,
): Promise<HTMLElement> {
  await user.click(
    await screen.findByRole("button", { name: "New conversation" }),
  );
  return await screen.findByRole("dialog", { name: "New conversation" });
}

/** Asserts the currently focused element is one of the dialog's own buttons (task 3.6). */
function expectFocusInsideDialog(): void {
  const focusable = Array.from(
    dialog().querySelectorAll<HTMLElement>("button:not([disabled])"),
  );
  expect(focusable).not.toHaveLength(0);
  expect(focusable).toContain(document.activeElement);
}

describe("conversation creation dialog (tasks 3.5–3.9)", () => {
  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
  });

  it("lists directory users the caller has no conversation with, excluding the caller (task 3.5)", async () => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    const user = await renderSignedInOnConversation();
    const dialogNode = await openDialog(user);

    // Carol and Dan have no conversation with Alice yet; Alice (the caller)
    // and Bob (her existing partner) must not be offered.
    expect(
      within(dialogNode).queryByRole("button", {
        name: "Start a conversation with Alice",
      }),
    ).toBeNull();
    expect(
      within(dialogNode).queryByRole("button", {
        name: "Start a conversation with Bob",
      }),
    ).toBeNull();
    expect(candidateButton("Carol")).toBeInTheDocument();
    expect(candidateButton("Dan")).toBeInTheDocument();
  });
});

describe("conversation creation dialog — exhausted directory (task 3.5)", () => {
  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    vi.spyOn(api, "fetchConversations").mockResolvedValue([
      CONVERSATION,
      { id: "conv-2", participants: [USER_A, USER_C] },
      { id: "conv-3", participants: [USER_A, USER_D] },
    ]);
  });

  it("states the exhausted directory in words and presents no empty selection list", async () => {
    const user = await renderSignedInOnConversation();
    await openDialog(user);

    expect(
      screen.getByText(/conversation open with everyone/),
    ).toBeInTheDocument();
    // No empty list — not a single candidate affordance is offered.
    expect(
      within(dialog()).queryByRole("button", { name: /Start a conversation/ }),
    ).toBeNull();
  });
});

describe("conversation creation dialog — focus (task 3.6)", () => {
  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
  });

  it("confines keyboard focus inside the dialog, wrapping at both ends", async () => {
    const user = await renderSignedInOnConversation();
    await openDialog(user);

    // Initial focus: the first candidate, once the directory resolved.
    expect(document.activeElement).toBe(candidateButton("Carol"));

    // Tab from the first candidate stays inside the dialog…
    fireEvent.keyDown(candidateButton("Carol"), { key: "Tab" });
    expectFocusInsideDialog();
    expect(document.activeElement).toBe(candidateButton("Dan"));
    // …from the last candidate it wraps to Cancel…
    fireEvent.keyDown(candidateButton("Dan"), { key: "Tab" });
    expectFocusInsideDialog();
    expect(document.activeElement).toBe(
      within(dialog()).getByRole("button", { name: "Cancel" }),
    );
    // …and Shift+Tab from the first element wraps back to the end: put focus
    // back on the first candidate (the wrap landed on Cancel), then Shift+Tab.
    candidateButton("Carol").focus();
    fireEvent.keyDown(candidateButton("Carol"), { key: "Tab", shiftKey: true });
    expectFocusInsideDialog();
    expect(document.activeElement).toBe(
      within(dialog()).getByRole("button", { name: "Cancel" }),
    );
  });

  it("returns focus to the `+` control on dismissal (Escape)", async () => {
    const user = await renderSignedInOnConversation();
    await openDialog(user);

    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );

    expect(document.activeElement).toBe(
      await screen.findByRole("button", { name: "New conversation" }),
    );
  });
});

describe("conversation creation dialog — success (task 3.7)", () => {
  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    vi.spyOn(api, "createConversation").mockResolvedValue(CONVERSATION_WITH_C);
  });

  it("closes, adds and selects in the rail, focuses the composer, and announces", async () => {
    const user = await renderSignedInOnConversation();
    await openDialog(user);

    await user.click(candidateButton("Carol"));

    // The dialog closes…
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    // …the rail gained the conversation and it IS the selected one…
    const carolRow = await screen.findByRole("button", { name: "Carol Offline" });
    expect(carolRow).toHaveAttribute("aria-current", "true");
    // …focus moved to the new conversation's composer…
    expect(
      await screen.findByRole("textbox", { name: "Message to Carol" }),
    ).toHaveFocus();
    // …and the change is announced in words.
    expect(
      screen.getByText("Conversation with Carol opened."),
    ).toBeInTheDocument();
  });
});

describe("conversation creation dialog — failure (task 3.7)", () => {
  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
    vi.spyOn(api, "fetchConversations").mockResolvedValue([CONVERSATION]);
    vi.spyOn(api, "createConversation").mockRejectedValue(
      new api.ApiError(404, "That person is not in the directory."),
    );
  });

  it("keeps the dialog open with the reason in words, adds nothing to the rail, and allows another attempt", async () => {
    const createSpy = api.createConversation as unknown as ReturnType<
      typeof vi.fn
    >;
    const user = await renderSignedInOnConversation();
    await openDialog(user);

    await user.click(candidateButton("Carol"));

    // The failure is stated in words…
    expect(await screen.findByText(/not in the directory/)).toBeInTheDocument();
    // …the dialog STAYS open…
    expect(dialog()).toBeInTheDocument();
    // …the rail is untouched (still only the Alice↔Bob pair, still selected)…
    const rail = screen.getByRole("navigation");
    expect(within(rail).queryByRole("button", { name: "Carol" })).toBeNull();
    expect(within(rail).getByRole("button", { name: "Bob Offline" })).toHaveAttribute(
      "aria-current",
      "true",
    );
    // …and another attempt is possible in place.
    await user.click(candidateButton("Carol"));
    expect(createSpy).toHaveBeenCalledTimes(2);
    expect(dialog()).toBeInTheDocument();
  });
});

describe("conversation creation dialog — in-flight guard (task 3.8)", () => {
  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
  });

  it("indicates progress in words and blocks a duplicate submission for the same person", async () => {
    const createSpy = vi.spyOn(api, "createConversation");
    let resolveCreate: ((conversation: Conversation) => void) | undefined;
    createSpy.mockImplementation(
      () =>
        new Promise<Conversation>((resolve) => {
          resolveCreate = resolve;
        }),
    );

    const user = await renderSignedInOnConversation();
    await openDialog(user);

    await user.click(candidateButton("Carol"));

    // While in flight, the person's affordance states the progress as a word
    // (Status-Is-Text) and is disabled — the duplicate submission is blocked
    // by the affordance itself.
    const carolButton = candidateButton("Carol");
    expect(carolButton).toBeDisabled();
    // Status-IS-Text: progress is a word, not only a spinner.
    expect(within(dialog()).getByText("Starting…")).toBeInTheDocument();

    // A second submission for the same person is impossible while in flight.
    await user.click(carolButton);
    expect(createSpy).toHaveBeenCalledTimes(1);

    // Resolving completes the success path (task 3.7) and re-arms the control.
    await act(async () => {
      resolveCreate?.(CONVERSATION_WITH_C);
      await Promise.resolve();
    });
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
  });
});

/**
 * Task 3.9: a `CONVERSATION_CREATED` event arrives on this session's socket
 * (someone else started a conversation with this user). The rail must add it
 * with an empty history — no refetch of the conversations listing, and the
 * active conversation must not change.
 */
describe("CONVERSATION_CREATED rail reconciliation (task 3.9)", () => {
  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
  });

  it("adds an unlisted conversation to the rail and does not change the active conversation", async () => {
    const fetchConversationsSpy = vi.spyOn(api, "fetchConversations");
    await renderSignedInOnConversation();

    const socket = captured[0]!;
    act(() => {
      socket.options.onEvent?.({
        type: "CONVERSATION_CREATED",
        conversation: CONVERSATION_WITH_C,
      });
    });

    // The rail lists the new conversation…
    const carolRow = await screen.findByRole("button", { name: "Carol Offline" });
    // …without a refetch (cache patch, per the spec's "without refetch")…
    expect(fetchConversationsSpy).toHaveBeenCalledTimes(1);
    // …and without changing the active conversation.
    expect(screen.getByRole("button", { name: "Bob Offline" })).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(carolRow).not.toHaveAttribute("aria-current");
  });

  it("is idempotent on re-delivery: a listed conversation is never added twice", async () => {
    await renderSignedInOnConversation();

    const socket = captured[0]!;
    act(() => {
      socket.options.onEvent?.({
        type: "CONVERSATION_CREATED",
        conversation: CONVERSATION_WITH_C,
      });
      socket.options.onEvent?.({
        type: "CONVERSATION_CREATED",
        conversation: CONVERSATION_WITH_C,
      });
    });

    await screen.findByRole("button", { name: "Carol Offline" });
    expect(
      screen.getAllByRole("button", { name: "Carol Offline" }),
    ).toHaveLength(1);
  });
});

/**
 * Slice 3 — rail previews (OpenSpec tasks 4.2–4.6, spec
 * `conversation-previews`). The rail rows render the server-provided
 * `lastMessage`, the client-side pending/failed override applies over it, and
 * MESSAGE_ACK / NEW_MESSAGE advance the preview by patching the cached
 * conversations array — never by refetching the listing.
 */
describe("rail previews (tasks 4.2–4.6)", () => {
  const LATEST_FROM_BOB_T = "2026-08-30T11:59:00.000Z";
  const LATEST_FROM_BOB = msg("m-latest", LATEST_FROM_BOB_T, {
    content: "Latest from Bob",
  });
  // The backend OMITS `lastMessage` for an empty history (task 4.0), so the
  // empty-history fixture is an object literal WITHOUT the key.
  const EMPTY_CONVERSATION: Conversation = {
    id: "conv-empty",
    participants: [USER_A, USER_C],
  };

  beforeEach(() => {
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
  });

  it("renders the server preview, and 'No messages yet' for the key-less empty-history form (task 4.2)", async () => {
    vi.spyOn(api, "fetchConversations").mockResolvedValue([
      { ...CONVERSATION, lastMessage: LATEST_FROM_BOB },
      EMPTY_CONVERSATION,
    ]);
    await renderSignedInOnConversation();

    expect(within(rail()).getByText("Latest from Bob")).toBeInTheDocument();
    expect(within(rail()).getByText(EMPTY_PREVIEW)).toBeInTheDocument();
    expect(within(rail()).getByText("No messages yet")).toBeInTheDocument();
    expect(api.fetchConversations).toHaveBeenCalledTimes(1);
  });

  it("overrides the server preview with 'Sending…' while the newest own message awaits acknowledgement (tasks 4.3/4.6)", async () => {
    vi.spyOn(api, "fetchConversations").mockResolvedValue([
      { ...CONVERSATION, lastMessage: LATEST_FROM_BOB },
    ]);
    const user = await renderSignedInOnConversation();
    expect(within(rail()).getByText("Latest from Bob")).toBeInTheDocument();

    const textbox = await screen.findByRole("textbox", {
      name: /message to bob/i,
    });
    await user.type(textbox, "Hello there");
    await user.click(screen.getByRole("button", { name: "Send" }));

    await waitFor(() =>
      expect(within(rail()).getByText("Sending…")).toBeInTheDocument(),
    );
    // The server preview is overridden, not shown alongside.
    expect(within(rail()).queryByText("Latest from Bob")).not.toBeInTheDocument();
  });

  it("overrides the server preview with 'Failed to send' when the newest own message was rejected (tasks 4.3/4.6)", async () => {
    vi.spyOn(api, "fetchConversations").mockResolvedValue([
      { ...CONVERSATION, lastMessage: LATEST_FROM_BOB },
    ]);
    const user = await renderSignedInOnConversation();

    const textbox = await screen.findByRole("textbox", {
      name: /message to bob/i,
    });
    await user.type(textbox, "This will fail");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await within(threadPane()).findByText("Sending…")).toBeInTheDocument();

    const socket = captured[0]!;
    const sent = socket.sendMessage.mock.calls[0]?.[0] as {
      clientMessageId: string;
    };
    act(() => {
      socket.options.onEvent?.({
        type: "ERROR",
        clientMessageId: sent.clientMessageId,
        code: "PERSISTENCE_ERROR",
        reason: "boom",
      });
    });

    await waitFor(() =>
      expect(within(rail()).getByText("Failed to send")).toBeInTheDocument(),
    );
    expect(within(rail()).queryByText("Latest from Bob")).not.toBeInTheDocument();
  });

  it("updates the preview on acknowledgement by cache patch, without refetching the listing (task 4.4)", async () => {
    const fetchConversationsSpy = vi
      .spyOn(api, "fetchConversations")
      .mockResolvedValue([{ ...CONVERSATION, lastMessage: LATEST_FROM_BOB }]);
    const user = await renderSignedInOnConversation();
    expect(fetchConversationsSpy).toHaveBeenCalledTimes(1);

    const textbox = await screen.findByRole("textbox", {
      name: /message to bob/i,
    });
    await user.type(textbox, "Hello there");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await within(threadPane()).findByText("Sending…")).toBeInTheDocument();

    const socket = captured[0]!;
    const sent = socket.sendMessage.mock.calls[0]?.[0] as {
      clientMessageId: string;
    };
    act(() => {
      socket.options.onEvent?.({
        type: "MESSAGE_ACK",
        clientMessageId: sent.clientMessageId,
        message: msg("server-1", "2026-08-30T12:00:00.000Z", {
          senderId: USER_A.id,
          content: "Hello there",
        }),
      });
    });

    // The override retires (its pending item is gone) and the acked message
    // IS the new server preview…
    await waitFor(() =>
      expect(within(rail()).getByText("Hello there")).toBeInTheDocument(),
    );
    // …and the listing was never refetched for it.
    expect(fetchConversationsSpy).toHaveBeenCalledTimes(1);
  });

  it("updates the preview on a received message by cache patch, without refetching the listing (task 4.4)", async () => {
    const fetchConversationsSpy = vi
      .spyOn(api, "fetchConversations")
      .mockResolvedValue([{ ...CONVERSATION, lastMessage: LATEST_FROM_BOB }]);
    await renderSignedInOnConversation();
    expect(fetchConversationsSpy).toHaveBeenCalledTimes(1);

    const socket = captured[0]!;
    act(() => {
      socket.options.onEvent?.({
        type: "NEW_MESSAGE",
        message: msg("m-in", "2026-08-30T12:30:00.000Z", {
          content: "Hi Alice",
        }),
      });
    });

    await waitFor(() =>
      expect(within(rail()).getByText("Hi Alice")).toBeInTheDocument(),
    );
    expect(within(rail()).queryByText("Latest from Bob")).not.toBeInTheDocument();
    expect(fetchConversationsSpy).toHaveBeenCalledTimes(1);
  });

  it("ignores a MESSAGE event for a conversation the rail does not list (message events never invent rows)", async () => {
    const fetchConversationsSpy = vi
      .spyOn(api, "fetchConversations")
      .mockResolvedValue([{ ...CONVERSATION, lastMessage: LATEST_FROM_BOB }]);
    await renderSignedInOnConversation();

    const socket = captured[0]!;
    act(() => {
      socket.options.onEvent?.({
        type: "NEW_MESSAGE",
        message: msg("m-stranger", "2026-08-30T12:30:00.000Z", {
          conversationId: "conv-unknown",
          content: "From an unlisted conversation",
        }),
      });
    });
    // No row invented; the listed row is untouched.
    expect(within(rail()).queryByRole("button", { name: "Carol" })).toBeNull();
    expect(within(rail()).getByText("Latest from Bob")).toBeInTheDocument();
    expect(fetchConversationsSpy).toHaveBeenCalledTimes(1);
  });
});

/**
 * Slice 4 — presence (OpenSpec tasks 5.4–5.5, 5.9, spec `presence`). The
 * wholesale-replacement rule lives at `SignedInShell` (the state's only
 * owner), the word-plus-dot rendering in the rail rows, so this file is the
 * integration seam, driving PRESENCE frames through the faked socket exactly
 * as the suites above do.
 */
describe("presence (tasks 5.4–5.5, 5.9)", () => {
  /** The `.presence` mark inside a rail row (dot + word). */
  function presenceMark(row: HTMLElement): HTMLElement {
    const mark = row.querySelector<HTMLElement>(".presence");
    expect(mark).not.toBeNull();
    return mark as HTMLElement;
  }

  beforeEach(() => {
    vi.spyOn(api, "fetchUsers").mockResolvedValue(DIRECTORY);
    vi.spyOn(api, "fetchMessages").mockResolvedValue([]);
  });

  it("the first event as a presence snapshot sets the initial online set, word plus dot (task 5.5)", async () => {
    await renderSignedInOnConversation();

    // Before any PRESENCE frame, the honest default is offline — in words.
    const bobRow = within(rail()).getByRole("button", { name: "Bob Offline" });
    const markBefore = presenceMark(bobRow);
    expect(markBefore).toHaveAttribute("data-state", "offline");
    expect(within(markBefore).getByText("Offline")).toBeInTheDocument();
    // The word is the signal; the dot is decoration only.
    expect(markBefore.querySelector(".presence-dot")).toHaveAttribute(
      "aria-hidden",
      "true",
    );

    // A PRESENCE frame as the socket's first event establishes the set.
    act(() => {
      captured[0]!.options.onEvent?.({
        type: "PRESENCE",
        online: [USER_B.id],
      });
    });

    const onlineRow = within(rail()).getByRole("button", { name: "Bob Online" });
    const markAfter = presenceMark(onlineRow);
    expect(markAfter).toHaveAttribute("data-state", "online");
    expect(within(markAfter).getByText("Online")).toBeInTheDocument();
    expect(markAfter.querySelector(".presence-dot")).not.toBeNull();
    // The word vanished with the state — no stale "Offline" beside "Online".
    expect(within(markAfter).queryByText("Offline")).not.toBeInTheDocument();
  });

  it("REPLACES the set wholesale: a partner absent from the new snapshot reads Offline, never merged (tasks 5.4/5.9)", async () => {
    // Two partners so the replacement is observable in BOTH directions.
    vi.spyOn(api, "fetchConversations").mockResolvedValue([
      CONVERSATION,
      CONVERSATION_WITH_C,
    ]);
    await renderSignedInOnConversation();

    const socket = captured[0]!;
    // First snapshot: Bob online, Carol not.
    act(() => {
      socket.options.onEvent?.({ type: "PRESENCE", online: [USER_B.id] });
    });
    expect(
      await within(rail()).findByRole("button", { name: "Bob Online" }),
    ).toBeInTheDocument();
    expect(
      within(rail()).getByRole("button", { name: "Carol Offline" }),
    ).toBeInTheDocument();

    // Second snapshot lists ONLY Carol. Were the client merging, Bob would
    // stay online; wholesale replacement drops him AND brings Carol online.
    act(() => {
      socket.options.onEvent?.({ type: "PRESENCE", online: [USER_C.id] });
    });

    await waitFor(() =>
      expect(
        within(rail()).getByRole("button", { name: "Carol Online" }),
      ).toBeInTheDocument(),
    );
    expect(
      within(rail()).getByRole("button", { name: "Bob Offline" }),
    ).toBeInTheDocument();

    // And the state travels one way only: an empty replacement set takes
    // everyone offline (the backend always serialises the empty array).
    act(() => {
      socket.options.onEvent?.({ type: "PRESENCE", online: [] });
    });
    expect(
      await within(rail()).findByRole("button", { name: "Carol Offline" }),
    ).toBeInTheDocument();
  });
});
