import { SidebarSection } from '../../shared/layout/navigation/nav-item.model';

export interface AcquisitionNavPermissions {
  admin?: boolean;
  canManageAcquisition?: boolean;
}

type TranslateFn = (key: string) => string;

export function buildAcquisitionSection(
  translate: TranslateFn,
  permissions: AcquisitionNavPermissions,
): SidebarSection[] {
  if (!permissions.admin && !permissions.canManageAcquisition) return [];

  return [
    {
      id: 'acquisition',
      menuKey: 'acquisition',
      label: translate('acquisition.menu.acquisition'),
      expandable: true,
      items: [
        {
          id: 'discover',
          label: translate('acquisition.menu.discover'),
          icon: 'pi-search',
          routerLink: ['/discover'],
        },
        {
          id: 'wanted',
          label: translate('acquisition.menu.wanted'),
          icon: 'pi-bookmark',
          routerLink: ['/wanted'],
        },
      ],
    },
  ];
}
