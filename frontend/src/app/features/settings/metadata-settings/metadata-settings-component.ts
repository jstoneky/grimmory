import {Component, effect, inject} from '@angular/core';
import {FormBuilder, ReactiveFormsModule} from '@angular/forms';
import {FormsModule} from '@angular/forms';
import {MetadataProviderSettingsComponent} from '../global-preferences/metadata-provider-settings/metadata-provider-settings.component';
import {MetadataRefreshOptions} from '../../metadata/model/request/metadata-refresh-options.model';
import {AppSettingsService} from '../../../shared/service/app-settings.service';
import {SettingsHelperService} from '../../../shared/service/settings-helper.service';
import {AppSettingKey, AppSettings} from '../../../shared/model/app-settings.model';
import {ToggleSwitch} from 'primeng/toggleswitch';
import {Select} from 'primeng/select';
import {MetadataMatchWeightsComponent} from '../global-preferences/metadata-match-weights/metadata-match-weights-component';
import {MetadataPersistenceSettingsComponent} from './metadata-persistence-settings/metadata-persistence-settings-component';
import {PublicReviewsSettingsComponent} from './public-reviews-settings/public-reviews-settings-component';
import {MetadataProviderFieldSelectorComponent} from '../../metadata/component/metadata-provider-field-selector/metadata-provider-field-selector.component';
import {TranslocoDirective} from '@jsverse/transloco';
import {LibraryService} from '../../book/service/library.service';

@Component({
  selector: 'app-metadata-settings-component',
  standalone: true,
  imports: [
    MetadataProviderSettingsComponent,
    ReactiveFormsModule,
    FormsModule,
    MetadataMatchWeightsComponent,
    ToggleSwitch,
    Select,
    MetadataPersistenceSettingsComponent,
    PublicReviewsSettingsComponent,
    MetadataProviderFieldSelectorComponent,
    TranslocoDirective
  ],
  templateUrl: './metadata-settings-component.html',
  styleUrl: './metadata-settings-component.scss'
})
export class MetadataSettingsComponent {

  currentMetadataOptions: MetadataRefreshOptions | undefined;

  private readonly fb = inject(FormBuilder);
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly settingsHelper = inject(SettingsHelperService);
  private readonly libraryService = inject(LibraryService);

  readonly form = this.fb.nonNullable.group({
    metadataDownloadOnBookdrop: [true],
  });

  selectedAutoImportLibraryId: number | null = null;
  selectedAutoImportPathId: number | null = null;

  private hasHydrated = false;

  private readonly syncSettingsEffect = effect(() => {
    const settings = this.appSettingsService.appSettings();
    if (!settings || this.hasHydrated) {
      return;
    }

    this.initializeSettings(settings);
    this.hasHydrated = true;
  });

  get autoImportLibraryOptions() {
    return this.libraryService.libraries().map(lib => ({
      label: lib.name,
      value: lib.id!
    }));
  }

  get autoImportPathOptions() {
    const lib = this.libraryService.libraries()
      .find(l => l.id === this.selectedAutoImportLibraryId);
    return lib?.paths.map(p => ({label: p.path, value: p.id!})) ?? [];
  }

  onMetadataDownloadOnBookdropToggle(checked: boolean): void {
    this.form.controls.metadataDownloadOnBookdrop.setValue(checked, {emitEvent: false});
    this.settingsHelper.saveSetting(AppSettingKey.METADATA_DOWNLOAD_ON_BOOKDROP, checked);
  }

  onMetadataSubmit(metadataRefreshOptions: MetadataRefreshOptions): void {
    this.currentMetadataOptions = metadataRefreshOptions;
    this.settingsHelper.saveSetting(AppSettingKey.QUICK_BOOK_MATCH, metadataRefreshOptions);
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

  private initializeSettings(settings: AppSettings): void {
    if (settings.defaultMetadataRefreshOptions) {
      this.currentMetadataOptions = settings.defaultMetadataRefreshOptions;
    }

    this.form.patchValue({
      metadataDownloadOnBookdrop: settings.metadataDownloadOnBookdrop ?? true,
    }, {emitEvent: false});

    this.selectedAutoImportLibraryId = settings.bookdropAutoImportLibraryId ?? null;
    this.selectedAutoImportPathId = settings.bookdropAutoImportPathId ?? null;
  }
}
