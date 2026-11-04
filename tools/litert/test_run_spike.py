import unittest
import json
from run_spike import next_deadline, model_lock_path, experiment_passed


class PhaseDeadlineTest(unittest.TestCase):
    def test_heartbeats_cannot_keep_hung_native_inference_alive(self):
        deadline = 120
        for now in (5, 60, 119, 121):
            deadline = next_deadline(deadline, {"phase": "progress"}, now, 120)
        self.assertEqual(deadline, 120)

    def test_return_from_submission_does_not_restart_generation(self):
        self.assertEqual(next_deadline(120, {"phase": "submitted"}, 90, 120), 120)

    def test_each_new_phase_has_its_own_budget(self):
        self.assertEqual(next_deadline(120, {"phase": "generate"}, 15, 120), 135)


class ModelSelectionTest(unittest.TestCase):
    def test_lfm_gpu_never_uses_cpu_weights(self):
        cpu = json.loads(model_lock_path("lfm", "CPU").read_text())
        gpu = json.loads(model_lock_path("lfm", "GPU").read_text())
        self.assertNotEqual(cpu["sha256"], gpu["sha256"])
        self.assertTrue(gpu["file"].endswith("_gpu.litertlm"))

    def test_qwen_backend_comparison_uses_identical_artifact(self):
        self.assertEqual(model_lock_path("qwen3", "CPU"), model_lock_path("qwen3", "GPU"))
        self.assertNotEqual(model_lock_path("qwen3", "GPU"), model_lock_path("lfm", "GPU"))

    def test_unknown_model_cannot_select_arbitrary_path(self):
        with self.assertRaises(KeyError):
            model_lock_path("../unexpected", "CPU")

    def test_gemma_gpu_file_cannot_be_selected_for_cpu(self):
        self.assertTrue(model_lock_path("gemma4", "GPU").is_file())
        with self.assertRaises(KeyError):
            model_lock_path("gemma4", "CPU")


class AcceptanceTest(unittest.TestCase):
    def good_event(self, index):
        return {"phase": "result", "case": index, "validEvidenceJson": True,
                "fieldMatches": dict.fromkeys(["analito", "valor", "unidade", "referencia", "evidencia"], True)}

    def test_two_complete_results_are_required(self):
        self.assertTrue(experiment_passed(True, False, False, -1, [self.good_event(0), self.good_event(1)]))
        self.assertFalse(experiment_passed(True, False, False, -1, [self.good_event(0)]))
        self.assertFalse(experiment_passed(True, False, False, -1, [self.good_event(0), self.good_event(0)]))

    def test_embedded_or_trimmed_matches_cannot_approve_invalid_response(self):
        bad = self.good_event(0)
        bad.update(validEvidenceJson=False, embeddedFieldMatchesDiagnosticOnly=bad["fieldMatches"],
                   trimmedFieldMatchesDiagnosticOnly=bad["fieldMatches"])
        self.assertFalse(experiment_passed(True, False, False, -1, [bad, self.good_event(1)]))

    def test_failed_field_timeout_or_crash_cannot_be_hidden_by_success_marker(self):
        events = [self.good_event(0), self.good_event(1)]
        self.assertFalse(experiment_passed(True, True, False, -1, events))
        self.assertFalse(experiment_passed(True, False, True, -1, events))
        self.assertFalse(experiment_passed(False, False, False, -1, events))
        events[0]["fieldMatches"]["referencia"] = False
        self.assertFalse(experiment_passed(True, False, False, -1, events))


if __name__ == "__main__":
    unittest.main()
