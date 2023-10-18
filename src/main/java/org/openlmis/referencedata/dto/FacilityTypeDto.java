/*
 * This program is part of the OpenLMIS logistics management information system platform software.
 * Copyright © 2017 VillageReach
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details. You should have received a copy of
 * the GNU Affero General Public License along with this program. If not, see
 * http://www.gnu.org/licenses.  For additional information contact info@OpenLMIS.org.
 */

package org.openlmis.referencedata.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.openlmis.referencedata.domain.FacilityType;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class FacilityTypeDto extends BaseDto
    implements FacilityType.Exporter, FacilityType.Importer {
  private String code;
  private String name;
  private String description;
  private Integer displayOrder;
  private Boolean active;
  private boolean primaryHealthCare;

  /**
   * Creates new facilityTypeDto based on given {@link FacilityType}.
   *
   * @param facilityType instance of FacilityType
   * @return new instance of FacilityTypeDto.
   */
  public static FacilityTypeDto newInstance(FacilityType facilityType) {
    if (facilityType == null) {
      return null;
    }
    FacilityTypeDto facilityTypeDto = new FacilityTypeDto();
    facilityType.export(facilityTypeDto);
    return facilityTypeDto;
  }
}