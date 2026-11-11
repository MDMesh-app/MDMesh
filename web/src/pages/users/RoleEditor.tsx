import { useState } from 'react';
import { managementError, saveRole, type Permission, type UserRole } from '../../api/users';
import { Button, Callout, Checkbox, Field, Input, Modal, SearchInput, Textarea } from '../../ui';

export function RoleEditor({
  target,
  permissions,
  onClose,
  onSaved,
}: {
  target: UserRole | 'new';
  permissions: Permission[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [draft, setDraft] = useState<UserRole>(
    target === 'new'
      ? { name: '', description: '', permissions: [] }
      : { ...target, permissions: target.permissions ?? [] },
  );
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async () => {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await saveRole(draft);
      onSaved();
    } catch (e) {
      setError(managementError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open
      title={target === 'new' ? 'New role' : `Edit ${target.name}`}
      dismissOnBackdrop={false}
      description="Permission changes apply to every user with this role on their next server request."
      onClose={() => {
        if (!busy) onClose();
      }}
      footer={
        <>
          <Button disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="role-form" disabled={busy}>
            {busy ? 'Saving…' : 'Save role'}
          </Button>
        </>
      }
    >
      {error && (
        <Callout tone="alert" role="alert">
          {error}
        </Callout>
      )}
      <form
        id="role-form"
        className="users-form"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <fieldset disabled={busy} className="users-fields">
          <Field label="Role name">
            <Input
              value={draft.name}
              required
              maxLength={50}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
          </Field>
          <Field label="Description">
            <Textarea
              value={draft.description ?? ''}
              maxLength={1000}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
            />
          </Field>
          <fieldset className="users-scope">
            <legend>Permissions ({draft.permissions.length} selected)</legend>
            <SearchInput value={q} onChange={setQ} placeholder="Filter permissions" aria-label="Filter permissions" />
            <div className="users-choices">
              {permissions
                .filter((p) => p.name.toLowerCase().includes(q.toLowerCase()))
                .map((p) => (
                  <label key={p.id}>
                    <Checkbox
                      checked={draft.permissions.some((v) => v.id === p.id)}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          permissions: e.target.checked
                            ? [...draft.permissions, p]
                            : draft.permissions.filter((v) => v.id !== p.id),
                        })
                      }
                    />
                    <span>
                      {p.name.replace(/_/g, ' ')} <small>({p.name})</small>
                    </span>
                  </label>
                ))}
            </div>
          </fieldset>
        </fieldset>
      </form>
    </Modal>
  );
}
