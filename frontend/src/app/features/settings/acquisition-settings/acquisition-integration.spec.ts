import {describe, expect, it} from 'vitest';
import {SettingsComponent, SettingsTab} from '../settings.component';
import {buildAcquisitionSection} from '../../book/acquisition-nav-section';
import {buildAcquisitionSection as reExportedSection} from '../../../shared/layout/layout-sidebar/sidebar-sections';
import translations from '../../../../i18n/en';

/**
 * Guard tests for the acquisition hooks that live inside upstream-owned
 * files. A nightly upstream merge that drops one of these single-line hooks
 * compiles and runs cleanly — these specs are what turns that silent loss
 * into a red build.
 */
describe('acquisition integration seams', () => {
  interface CanOpenTab {
    canOpenTab(tab: SettingsTab): boolean;
  }

  const canOpenTab = (permissions: object | undefined) =>
    (SettingsComponent.prototype as unknown as CanOpenTab).canOpenTab.call(
      {userService: {currentUser: () => (permissions ? {permissions} : null)}},
      SettingsTab.AcquisitionSettings,
    );

  it('registers the acquisition settings tab', () => {
    expect(SettingsTab.AcquisitionSettings).toBe('acquisition');
  });

  it('gates the settings tab on admin or canManageAcquisition', () => {
    expect(canOpenTab({admin: true})).toBe(true);
    expect(canOpenTab({canManageAcquisition: true})).toBe(true);
    expect(canOpenTab({canManageMetadataConfig: true})).toBe(false);
    expect(canOpenTab(undefined)).toBe(false);
  });

  it('builds the sidebar section for permitted users only', () => {
    const t = (key: string) => key;
    expect(buildAcquisitionSection(t, {admin: true})).toHaveLength(1);
    expect(buildAcquisitionSection(t, {canManageAcquisition: true})[0].id).toBe('acquisition');
    expect(buildAcquisitionSection(t, {})).toHaveLength(0);

    const items = buildAcquisitionSection(t, {admin: true})[0].items ?? [];
    expect(items.map(item => item.id)).toEqual(['discover', 'wanted']);
  });

  it('keeps the sidebar re-export hook in sidebar-sections', () => {
    expect(reExportedSection).toBe(buildAcquisitionSection);
  });

  it('registers the acquisition i18n namespace', () => {
    const acquisition = (translations as Record<string, unknown>)['acquisition'] as Record<string, unknown>;
    expect(acquisition).toBeDefined();
    expect(acquisition['settingsTab']).toBe('Acquisition');
    expect((acquisition['menu'] as Record<string, string>)['discover']).toBeTruthy();
  });
});
