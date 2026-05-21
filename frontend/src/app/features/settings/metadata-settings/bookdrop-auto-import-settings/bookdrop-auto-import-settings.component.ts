import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Select } from 'primeng/select';
import { AppSettingKey } from '../../../../shared/model/app-settings.model';
import { AppSettingsService } from '../../../../shared/service/app-settings.service';
import { SettingsHelperService } from '../../../../shared/service/settings-helper.service';
import { LibraryService } from '../../../book/service/library.service';

@Component({
  selector: 'app-bookdrop-auto-import-settings',
  standalone: true,
  imports: [FormsModule, Select],
  templateUrl: './bookdrop-auto-import-settings.component.html',
})
export class BookdropAutoImportSettingsComponent {
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly settingsHelper = inject(SettingsHelperService);
  private readonly libraryService = inject(LibraryService);

  selectedAutoImportLibraryId: number | null = null;
  selectedAutoImportPathId: number | null = null;

  private hasHydrated = false;

  constructor() {
    const settings = this.appSettingsService.appSettings();
    if (settings && !this.hasHydrated) {
      this.selectedAutoImportLibraryId = settings.bookdropAutoImportLibraryId ?? null;
      this.selectedAutoImportPathId = settings.bookdropAutoImportPathId ?? null;
      this.hasHydrated = true;
    }
  }

  get autoImportLibraryOptions() {
    return this.libraryService.libraries().map(lib => ({
      label: lib.name,
      value: lib.id!,
    }));
  }

  get autoImportPathOptions() {
    const lib = this.libraryService.libraries()
      .find(l => l.id === this.selectedAutoImportLibraryId);
    return lib?.paths.map(p => ({ label: p.path, value: p.id! })) ?? [];
  }

  onAutoImportLibraryChange(libraryId: number | null): void {
    this.selectedAutoImportLibraryId = libraryId;
    this.selectedAutoImportPathId = null;
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_LIBRARY_ID, libraryId ?? '');
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_PATH_ID, '');
  }

  onAutoImportPathChange(pathId: number | null): void {
    this.selectedAutoImportPathId = pathId;
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_PATH_ID, pathId ?? '');
  }
}
