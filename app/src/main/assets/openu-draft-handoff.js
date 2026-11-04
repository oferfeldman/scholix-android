/* Install as a bookmarklet (prefix the compacted code with javascript:).
 * Run on Opal with the actual upload/edit form open. No secrets leave the page.
 */
(() => {
    if (location.origin !== 'https://opal.openu.ac.il') {
        alert('Open the assignment on the official Opal website first.');
        return;
    }
    const frame = document.getElementById('ouilassign_submission_frame');
    const formDocument = frame?.contentDocument || document;
    const form = formDocument.querySelector('form input[name="submissionfile"]')?.form;
    const manager = form?.querySelector('[id^="filemanager-"]');
    const course = document.body.className.match(/\bcourse-(\d+)\b/)?.[1];
    const cmid = document.body.className.match(/\bcmid-(\d+)\b/)?.[1];
    const assign = form?.querySelector('input[name="assignid"]')?.value;
    const itemid = form?.querySelector('input[name="submissionfile"]')?.value;
    const client = manager?.id.slice('filemanager-'.length);
    if (![course, cmid, assign, itemid].every(id => /^[1-9][0-9]*$/.test(id || '')) || !client) {
        alert('Open the upload/update form inside the assignment page, then use this bookmark again.');
        return;
    }
    location.href = 'scholix-openu://draft?' + new URLSearchParams({course, cmid, assign, itemid, client});
})();
