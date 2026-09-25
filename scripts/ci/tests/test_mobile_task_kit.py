"""Task Kit guard: every task button goes through TaskCommands to the engine that owns the task.

alpha.27 shipped "I'm done does nothing": a surface talked to the wrong engine directly. These rules keep surfaces
from reaching engines around the bus again.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def sources():
    for path in BASE.rglob("*.kt"):
        yield path.relative_to(BASE).as_posix(), path.read_text(encoding="utf-8")


def callers(pattern: str) -> set[str]:
    regex = re.compile(pattern)
    return {name for name, text in sources() if regex.search(text)}


class TaskKitGuards(unittest.TestCase):
    def test_classic_foreground_commands_only_through_its_controller(self):
        self.assertEqual(callers(r"commandForegroundTask\("),
                         {"ui/overlay/OverlayChromeRuntime.kt", "task/TaskCommands.kt"})

    def test_workspace_service_intents_only_from_task_kit(self):
        self.assertEqual(callers(r"WorkspaceTasks\.commandIntent\("), {"task/TaskCommands.kt"})

    def test_mission_commands_only_from_task_kit(self):
        self.assertEqual(callers(r"MindMissions\.(ownerDone|ownerTakesPhone)\("), {"task/TaskCommands.kt"})

    def test_legacy_command_entry_point_delegates_to_the_bus(self):
        state = (BASE / "runtime/background/WorkspaceTaskState.kt").read_text(encoding="utf-8")
        body = state[state.index("fun command(context: Context, task: WorkspaceTaskUi, action: String)"):]
        body = body[: body.index("\n    }") ]
        self.assertIn("TaskCommands.send", body)
        self.assertNotIn("startService", body)

    def test_notification_buttons_use_task_kit(self):
        notification = (BASE / "runtime/background/TaskProgressNotification.kt").read_text(encoding="utf-8")
        self.assertIn("TaskCommands.pendingIntent", notification)
        self.assertNotIn("PendingIntent.getService", notification)

    def test_foreground_commands_reaching_the_workspace_service_are_forwarded_to_the_bus(self):
        service = (BASE / "runtime/background/WorkspaceTaskService.kt").read_text(encoding="utf-8")
        self.assertIn("TaskCommands.send(applicationContext, shared.taskId", service)

    def test_receiver_is_declared_private(self):
        manifest = (ROOT / "apps/mobile/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertRegex(manifest, r'<receiver android:name="\.task\.TaskCommandReceiver" android:exported="false" />')


if __name__ == "__main__":
    unittest.main()
