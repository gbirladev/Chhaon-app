package org.smartgurukul.chhaon.wallet;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.smartgurukul.chhaon.wallet.WalletService.CreateRequest;
import org.smartgurukul.chhaon.wallet.WalletService.DocumentDto;
import org.smartgurukul.chhaon.wallet.WalletService.Fields;
import org.smartgurukul.chhaon.wallet.WalletService.PagesRequest;
import org.smartgurukul.chhaon.wallet.WalletService.PathsRequest;
import org.smartgurukul.chhaon.wallet.WalletService.Saved;
import org.smartgurukul.chhaon.wallet.WalletService.UploadRequest;
import org.smartgurukul.chhaon.wallet.WalletService.UploadSlot;
import org.smartgurukul.chhaon.wallet.WalletService.Usage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Reports wallet (the Reports tab). Only members of the person's family circle; anything else is 404.
 *
 * <pre>
 * GET    /api/v1/recipients/{id}/documents            the reports (newest paper first, at most 200), with their pages
 * GET    /api/v1/recipients/{id}/documents/usage      {used, max}  (max null = no plan limit)
 * POST   /api/v1/recipients/{id}/documents/uploads    {kind: pdf|jpg|png|webp} -> {path, uploadUrl, thumbPath, thumbUploadUrl}
 * POST   /api/v1/recipients/{id}/documents/links      {paths} -> {path: signed read address}  (5 minutes)
 * POST   /api/v1/recipients/{id}/documents/discard    {paths}  uploaded files that were not saved into a report
 * POST   /api/v1/recipients/{id}/documents            {title, category, docDate, doctor, notes, files: [{path, thumbPath, mime, sizeBytes}]}
 * GET    /api/v1/documents/{docId}                    one report
 * PUT    /api/v1/documents/{docId}                    {title, category, docDate, doctor, notes}
 * POST   /api/v1/documents/{docId}/files              {files: [...]}  more pages
 * DELETE /api/v1/documents/{docId}/files/{fileId}     one page (not the last one)
 * DELETE /api/v1/documents/{docId}                    the report and its files
 * </pre>
 * Addresses are relative to {@code <Supabase URL>/storage/v1}. Refusals carry a reason the app words: 409 plan_documents,
 * too_many_documents, too_many_files, storage_full, bad_path, last_page; 503 storage_off; 502 storage_failed.
 */
@RestController
@RequestMapping("/api/v1")
public class WalletController {

    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    @GetMapping("/recipients/{id}/documents")
    public List<DocumentDto> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.list(user.id(), id);
    }

    @GetMapping("/recipients/{id}/documents/usage")
    public Usage usage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.usage(user.id(), id);
    }

    @PostMapping("/recipients/{id}/documents/uploads")
    public UploadSlot upload(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody UploadRequest in) {
        return service.uploadSlot(user.id(), id, in);
    }

    @PostMapping("/recipients/{id}/documents/links")
    public Map<String, String> links(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody PathsRequest in) {
        return service.links(user.id(), id, in);
    }

    @PostMapping("/recipients/{id}/documents/discard")
    public ResponseEntity<Void> discard(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody PathsRequest in) {
        service.discard(user.id(), id, in);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recipients/{id}/documents")
    public Saved create(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody CreateRequest in) {
        return service.create(user.id(), id, in);
    }

    @GetMapping("/documents/{docId}")
    public DocumentDto get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID docId) {
        return service.get(user.id(), docId);
    }

    @PutMapping("/documents/{docId}")
    public DocumentDto update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID docId, @RequestBody Fields in) {
        return service.update(user.id(), docId, in);
    }

    @PostMapping("/documents/{docId}/files")
    public Saved addPages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID docId, @RequestBody PagesRequest in) {
        return service.addPages(user.id(), docId, in);
    }

    @DeleteMapping("/documents/{docId}/files/{fileId}")
    public ResponseEntity<Void> removePage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID docId, @PathVariable UUID fileId) {
        service.removePage(user.id(), docId, fileId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID docId) {
        service.delete(user.id(), docId);
        return ResponseEntity.noContent().build();
    }
}
