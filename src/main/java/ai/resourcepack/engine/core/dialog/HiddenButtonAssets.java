package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.dialogruntime.DialogHiddenButton;
import ai.resourcepack.dialogruntime.DialogShaderRevision;
import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.util.Map;
import java.util.Set;

/**
 * Writes the shader that hides a dialog's button into a bundle holding a
 * dialog written with {@code hide-button: true} (see
 * {@link DialogHiddenButton}), and into no other: a server that never asks
 * for it ships the pack it always did.
 *
 * <p>Only in the {@code rpai_dialog_v*} overlays, never the base, as Studio
 * ships it: a core shader written for one renderer does not compile on
 * another, and one that does not compile drops the whole pack. A Studio
 * pack in the content folder may already carry the same file; the newer
 * revision stays ({@link DialogShaderRevision}), as when two such packs meet.
 */
public final class HiddenButtonAssets implements PackContributor {

    private final Set<ContentId> dialogs;

    /** @param dialogs the dialogs whose button is hidden ({@link DialogDefinitions.Result#hiddenButtons()}) */
    public HiddenButtonAssets(Set<ContentId> dialogs) {
        this.dialogs = dialogs;
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        if (dialogs.stream().noneMatch(id -> bundle.namespaces().contains(id.namespace()))) {
            return;
        }
        for (DialogHiddenButton.Overlay overlay : DialogHiddenButton.overlays()) {
            into.overlay(overlay.directory(), overlay.minFormat(), overlay.maxFormat());
        }
        for (Map.Entry<String, byte[]> file : DialogHiddenButton.files().entrySet()) {
            byte[] had = into.read(file.getKey()).orElse(null);
            if (had != null && DialogShaderRevision.pick(had, file.getValue()) == had) {
                continue;
            }
            into.add(file.getKey(), file.getValue());
        }
    }
}
