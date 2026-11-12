from clipsync.cloud import Firestore


class Recording(Firestore):
    def __init__(self, ids):
        super().__init__(auth=None, project_id="p", uid="u")
        self.ids, self.deleted, self.asked = ids, [], None

    def query(self, structured):
        self.asked = structured
        return [(i, {}) for i in self.ids]

    def delete(self, doc_id):
        self.deleted.append(doc_id)


def test_wipe_deletes_everything_headers_first_and_reads_names_only():
    fs = Recording(["presence_a", "chunk_x_0", "clip_x", "presence_b"])
    assert fs.wipe() == 4
    assert fs.deleted[0] == "clip_x"
    assert sorted(fs.deleted) == ["chunk_x_0", "clip_x", "presence_a", "presence_b"]
    assert fs.asked["select"] == {"fields": [{"fieldPath": "__name__"}]}


def test_wipe_of_an_empty_account_deletes_nothing():
    fs = Recording([])
    assert fs.wipe() == 0 and fs.deleted == []
