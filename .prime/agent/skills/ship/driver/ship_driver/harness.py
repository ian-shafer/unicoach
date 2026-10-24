"""The agent primitives the driver uses, behind one small interface so tests can inject a fake.

LiveHarness reads the kernel globals (rlm, bash, agent_message, agent_observe, rlm_heartbeat, compact)
from `__main__`, which is where the Prime Agent kernel defines them; nothing else is needed from the caller.
"""
import os
import shlex
import sys
from pathlib import Path


class LiveHarness:
    def __init__(self, namespace=None):
        self.ns = namespace if namespace is not None else vars(sys.modules["__main__"])

    def __getattr__(self, name):
        try:
            return self.ns[name]
        except KeyError:
            raise AttributeError(f"the kernel has no global {name!r}; LiveHarness needs the agent kernel") from None

    async def spawn(self, prompt, name, model=None) -> str:
        """Spawn a child; returns its child id, the one handle the driver keeps."""
        return (await self.rlm.spawn(prompt, name=name, model=model)).rlm_child_id

    async def busy(self, name) -> bool:
        """Streaming, compacting, or holding a queued message. A child absent from the roster is not busy; a roster
        that cannot be read raises, because "could not ask" is not "not busy"."""
        roster = (await self.agent_observe.list_agents())["agents"]
        agent = next((a for a in roster if a.get("relationship") == "child" and a.get("sessionName") == name), None)
        if agent is None:
            return False
        return bool(agent.get("isStreaming") or agent.get("isCompacting") or agent.get("queuedCount"))

    async def list_subagents(self):
        return await self.rlm.list_subagents()

    async def delete(self, child_id):
        return await self.rlm.delete_subagent(child_id)

    async def send(self, name, message):
        return await self.agent_message.send(message, receiver_role="child", receiver_name=name)

    def wake(self, text):
        """Wake the idle coordinator: a background bash() command's completion reaches the agent as a notification
        carrying its output, so the echo IS the wake. print() would reach no one."""
        self.bash(f"echo {shlex.quote(text)}")

    async def session_id(self):
        roster = (await self.agent_observe.list_agents())["agents"]
        current = [agent["sessionId"] for agent in roster if agent.get("isCurrent")]
        if len(current) != 1:
            raise RuntimeError(f"agent_observe.list_agents() marks {len(current)} agents isCurrent, not exactly one: "
                               f"{[(agent.get('sessionName'), agent.get('sessionId')) for agent in roster]}")
        return current[0]

    def agent_dir(self) -> Path:
        """Prime Agent's own directory: the rlm-ledger the Spend section reads children's session files from."""
        return Path(os.environ["PRIME_AGENT_CODING_AGENT_DIR"])

    async def session_log(self) -> Path:
        """This session's transcript: a root session's is under <agent dir>/sessions, a child's in its artifacts."""
        session = await self.session_id()
        candidates = [self.agent_dir() / "sessions" / f"{session}.jsonl"]
        session_dir = os.environ.get("PRIME_AGENT_SESSION_DIR")
        if session_dir is not None:
            candidates.append(Path(session_dir) / f"{session}.jsonl")
        return next((path for path in candidates if path.is_file()), candidates[0])

    async def heartbeat_create(self, instruction, interval, label):
        made = await self.rlm_heartbeat.create(instruction, interval=interval, label=label,
                                               delivery_mode="follow_up")
        heartbeat_id = (made.get("heartbeat") or {}).get("id")
        if heartbeat_id is None:
            raise RuntimeError(f"rlm_heartbeat.create(label={label!r}) returned no heartbeat id: {made!r}")
        return heartbeat_id

    async def heartbeat_update(self, heartbeat_id, status):
        await self.rlm_heartbeat.update(heartbeat_id, status=status)

    async def heartbeat_delete(self, heartbeat_id):
        await self.rlm_heartbeat.delete(heartbeat_id)

    async def compact(self, instructions):
        await self.ns["compact"].run(instructions)
